package ferry

import scala.collection.immutable.{Queue, TreeMap}

import zio.{Chunk, NonEmptyChunk}

/**
 * The waiting room behind [[Ferry.perKey]]: one lane per key, as a pure value so that every transition can be tested on
 * its own and checked against the TLA+ model in `formal/PerKey.tla`.
 *
 * A key has a lane while it has items waiting or a call in flight. A lane keeps its items in ascending rank, ties in
 * arrival order, and is in exactly one state:
 *   - `Waiting(deadline)`: holds a partial batch whose oldest item is not yet due;
 *   - `Ready`: in line to be taken, because it is full or due;
 *   - `Busy`: its batch, or an input claimed by its caller, is in flight. Items for it keep accumulating, but it is
 *     never taken again until released.
 *
 * Ready lanes are served first come, first served. A lane that still holds items when its batch completes joins the
 * back of the line, so a key that is always busy cannot starve the others.
 */
final private[ferry] case class Lanes[K, X](
  limits: Lanes.Limits,
  lanes: Map[K, Lanes.Lane[X]],
  ready: Queue[K],
  waiting: TreeMap[Long, Vector[K]],
  queued: Int,
  weight: Long,
  busy: Int
):
  import Lanes.*

  /**
   * Marks `key` busy for an input its caller sends on its own, without a worker; `None` unless direct calls are on, the
   * key is idle (no lane) and batches are not delayed. Inputs for the key that arrive meanwhile wait in its lane, as
   * behind any batch.
   */
  def claim(key: K): Option[Lanes[K, X]] =
    Option.when(limits.directIdleKeys && limits.maxBatchDelayNanos == 0 && !lanes.contains(key))(
      copy(lanes = lanes.updated(key, Lane(Vector.empty[Item[X]], 0L, Status.Busy)), busy = busy + 1)
    )

  /** Adds `item` to the lane of `key`, or returns `None` if that would exceed the count or weight bound. */
  def admit(key: K, item: Item[X]): Option[Lanes[K, X]] =
    if queued >= limits.maxQueueing || item.weight > limits.maxQueuedWeight - weight then None
    else
      val counted = copy(queued = queued + 1, weight = weight + item.weight)
      Some(lanes.get(key) match
        case None       => counted.schedule(key, Lane(Vector(item), item.weight, Status.Busy))
        case Some(lane) =>
          val grown = lane.copy(items = lane.inserted(item), weight = lane.weight + item.weight)
          grown.status match
            case Status.Waiting(deadline) if grown.isFull(limits) => counted.unwait(key, deadline).schedule(key, grown)
            case _                                                => counted.copy(lanes = lanes.updated(key, grown)))

  /**
   * Takes the next batch at time `now`: from the first ready lane, after promoting every lane that is due by `now`. The
   * lane becomes busy. Without a batch to take, returns the next deadline, if any lane is waiting for one.
   */
  def next(now: Long): Either[Option[Long], (K, NonEmptyChunk[X], Lanes[K, X])] =
    val promoted = promote(now)
    promoted.ready.dequeueOption match
      case None              => Left(promoted.waiting.headOption.map(_._1))
      case Some((key, rest)) =>
        val lane                       = promoted.lanes(key)
        val (batch, remaining, weight) = lane.firstBatch(limits)
        Right(
          (
            key,
            batch.map(_.value),
            promoted.copy(
              lanes = promoted.lanes.updated(key, Lane(remaining, lane.weight - weight, Status.Busy)),
              ready = rest,
              queued = promoted.queued - batch.size,
              weight = promoted.weight - weight,
              busy = promoted.busy + 1
            )
          )
        )

  /** Marks the batch of `key` as complete: its lane goes back in line if it holds more items, and away otherwise. */
  def release(key: K): Lanes[K, X] =
    lanes.get(key) match
      case Some(lane) if lane.status == Status.Busy =>
        val freed = copy(busy = busy - 1)
        if lane.items.isEmpty then freed.copy(lanes = lanes - key) else freed.schedule(key, lane)
      case _ => this

  /** Removes every waiting item, for shutdown. Lanes with a batch in flight stay busy until released. */
  def drain: (Chunk[X], Lanes[K, X]) =
    val items    = Chunk.fromIterable(lanes.values.flatMap(_.items.map(_.value)))
    val inFlight = lanes.collect {
      case (key, lane) if lane.status == Status.Busy => key -> Lane(Vector.empty[Item[X]], 0L, lane.status)
    }
    (
      items,
      copy(lanes = inFlight, ready = Queue.empty, waiting = TreeMap.empty[Long, Vector[K]], queued = 0, weight = 0L)
    )

  /** Puts a lane that holds items in line: ready if it is full, waiting for its oldest item's deadline otherwise. */
  private def schedule(key: K, lane: Lane[X]): Lanes[K, X] =
    if lane.isFull(limits) then copy(lanes = lanes.updated(key, lane.copy(status = Status.Ready)), ready = ready :+ key)
    else
      val deadline = saturatingAdd(lane.items.iterator.map(_.submittedAt).min, limits.maxBatchDelayNanos)
      copy(
        lanes = lanes.updated(key, lane.copy(status = Status.Waiting(deadline))),
        waiting = waiting.updated(deadline, waiting.getOrElse(deadline, Vector.empty) :+ key)
      )

  private def unwait(key: K, deadline: Long): Lanes[K, X] =
    val others = waiting.getOrElse(deadline, Vector.empty).filterNot(_ == key)
    copy(waiting = if others.isEmpty then waiting - deadline else waiting.updated(deadline, others))

  private def promote(now: Long): Lanes[K, X] =
    waiting.headOption match
      case Some((deadline, keys)) if deadline <= now =>
        copy(
          lanes = keys.foldLeft(lanes)((acc, key) => acc.updated(key, acc(key).copy(status = Status.Ready))),
          ready = ready ++ keys,
          waiting = waiting - deadline
        ).promote(now)
      case _ => this

object Lanes:

  final case class Limits(
    maxBatchSize: Int,
    maxBatchWeight: Long,
    maxBatchDelayNanos: Long,
    maxQueueing: Int,
    maxQueuedWeight: Long,
    directIdleKeys: Boolean
  )

  final case class Item[X](value: X, weight: Long, submittedAt: Long, rank: Long = 0L)

  enum Status:
    case Waiting(deadline: Long)
    case Ready
    case Busy

  final case class Lane[X](items: Vector[Item[X]], weight: Long, status: Status):

    def isFull(limits: Limits): Boolean =
      items.size >= limits.maxBatchSize || weight >= limits.maxBatchWeight

    /**
     * `items` with `item` after every item of a rank up to its own. Inputs mostly arrive in rank order: search from the
     * end.
     */
    def inserted(item: Item[X]): Vector[Item[X]] =
      val at = items.lastIndexWhere(_.rank <= item.rank) + 1
      if at == items.size then items :+ item else items.patch(at, Vector(item), 0)

    /** The first items that fit in one batch: always at least one, even if it alone exceeds the weight limit. */
    def firstBatch(limits: Limits): (NonEmptyChunk[Item[X]], Vector[Item[X]], Long) =
      @annotation.tailrec
      def loop(size: Int, weight: Long): (Int, Long) =
        if size < items.size && size < limits.maxBatchSize && items(size).weight <= limits.maxBatchWeight - weight then
          loop(size + 1, weight + items(size).weight)
        else (size, weight)
      val (size, weight) = loop(1, items.head.weight)
      val (batch, rest)  = items.splitAt(size)
      (NonEmptyChunk.fromIterable(batch.head, batch.tail), rest, weight)

  def empty[K, X](limits: Limits): Lanes[K, X] =
    Lanes(limits, Map.empty, Queue.empty, TreeMap.empty[Long, Vector[K]], 0, 0L, 0)

  private def saturatingAdd(a: Long, b: Long): Long =
    if b > 0 && a > Long.MaxValue - b then Long.MaxValue else a + b
