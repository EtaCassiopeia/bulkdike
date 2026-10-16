package bulkdike

import scala.collection.immutable.{Queue, TreeMap}

import zio.{Chunk, NonEmptyChunk}

/**
 * The waiting room behind [[BulkDike.perKey]]: one lane per key, as a pure value so that every transition can be tested
 * on its own and checked against the TLA+ model in `formal/PerKey.tla`.
 *
 * A key has a lane while it has items waiting or a batch in flight. A lane is in exactly one state:
 *   - `Waiting(deadline)`: holds a partial batch whose oldest item is not yet due;
 *   - `Ready`: in line to be taken, because it is full or due;
 *   - `Busy`: its batch is in flight. Items for it keep accumulating, but it is never taken again until released.
 *
 * Ready lanes are served first come, first served. A lane that still holds items when its batch completes joins the
 * back of the line, so a key that is always busy cannot starve the others.
 */
final private[bulkdike] case class Lanes[K, X](
  limits: Lanes.Limits,
  lanes: Map[K, Lanes.Lane[X]],
  ready: Queue[K],
  waiting: TreeMap[Long, Vector[K]],
  queued: Int,
  weight: Long,
  busy: Int
):
  import Lanes.*

  /** Adds `item` to the lane of `key`, or returns `None` if that would exceed the count or weight bound. */
  def admit(key: K, item: Item[X]): Option[Lanes[K, X]] =
    if queued >= limits.maxQueueing || item.weight > limits.maxQueuedWeight - weight then None
    else
      val counted = copy(queued = queued + 1, weight = weight + item.weight)
      Some(lanes.get(key) match
        case None       => counted.schedule(key, Lane(Queue(item), item.weight, Status.Busy))
        case Some(lane) =>
          val grown = lane.copy(items = lane.items.enqueue(item), weight = lane.weight + item.weight)
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
        val (batch, remaining, weight) = lane.take(limits)
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
      case (key, lane) if lane.status == Status.Busy => key -> Lane(Queue.empty[Item[X]], 0L, lane.status)
    }
    (
      items,
      copy(lanes = inFlight, ready = Queue.empty, waiting = TreeMap.empty[Long, Vector[K]], queued = 0, weight = 0L)
    )

  /** Puts a lane that holds items in line: ready if it is full, waiting for its oldest item's deadline otherwise. */
  private def schedule(key: K, lane: Lane[X]): Lanes[K, X] =
    if lane.isFull(limits) then copy(lanes = lanes.updated(key, lane.copy(status = Status.Ready)), ready = ready :+ key)
    else
      val deadline = saturatingAdd(lane.items.head.submittedAt, limits.maxBatchDelayNanos)
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
    maxQueuedWeight: Long
  )

  final case class Item[X](value: X, weight: Long, submittedAt: Long)

  enum Status:
    case Waiting(deadline: Long)
    case Ready
    case Busy

  final case class Lane[X](items: Queue[Item[X]], weight: Long, status: Status):

    def isFull(limits: Limits): Boolean =
      items.size >= limits.maxBatchSize || weight >= limits.maxBatchWeight

    /** The oldest items that fit in one batch: always at least one, even if it alone exceeds the weight limit. */
    def take(limits: Limits): (NonEmptyChunk[Item[X]], Queue[Item[X]], Long) =
      val (first, rest) = items.dequeue
      @annotation.tailrec
      def loop(
        batch: NonEmptyChunk[Item[X]],
        rest: Queue[Item[X]],
        weight: Long
      ): (NonEmptyChunk[Item[X]], Queue[Item[X]], Long) =
        rest.dequeueOption match
          case Some((item, more))
              if batch.size < limits.maxBatchSize && item.weight <= limits.maxBatchWeight - weight =>
            loop(batch :+ item, more, weight + item.weight)
          case _ => (batch, rest, weight)
      loop(NonEmptyChunk.single(first), rest, first.weight)

  def empty[K, X](limits: Limits): Lanes[K, X] =
    Lanes(limits, Map.empty, Queue.empty, TreeMap.empty[Long, Vector[K]], 0, 0L, 0)

  private def saturatingAdd(a: Long, b: Long): Long =
    if b > 0 && a > Long.MaxValue - b then Long.MaxValue else a + b
