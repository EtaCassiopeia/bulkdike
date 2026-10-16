package bulkdike

import scala.collection.immutable.Queue

import zio.*
import zio.test.*

import bulkdike.Lanes.*

/**
 * Drives the pure lane structure behind `BulkDike.perKey` through random sequences of claims, admissions, takes and
 * releases, and checks every step against a simple model: one FIFO of waiting items per key, and the set of busy keys.
 */
object LanesSpec extends ZIOSpecDefault:

  enum Op:
    case Claim(key: Int)
    case Admit(key: Int, weight: Long, elapse: Long)
    case Next(elapse: Long)
    case Release(pick: Int)
    case Drain

  private val limits: Gen[Any, Limits] =
    for
      size        <- Gen.int(1, 4)
      batchWeight <- Gen.long(1, 8)
      delay       <- Gen.long(0, 3)
      queueing    <- Gen.int(size, 10)
      queued      <- Gen.long(batchWeight, 20)
    yield Limits(size, batchWeight, delay, queueing, queued)

  private val op: Gen[Any, Op] = Gen.oneOf(
    Gen.int(0, 3).map(Op.Claim(_)),
    (Gen.int(0, 3) <*> Gen.long(0, 5) <*> Gen.long(0, 1)).map(Op.Admit(_, _, _)),
    Gen.long(0, 2).map(Op.Next(_)),
    Gen.int(0, 3).map(Op.Release(_)),
    Gen.const(Op.Drain)
  )

  /** What a correct implementation must be equivalent to, plus the clock. */
  final private case class Model(
    now: Long = 0,
    serial: Int = 0,
    waiting: Map[Int, Queue[Item[Int]]] = Map.empty,
    busy: Set[Int] = Set.empty
  ):
    def items: Iterable[Item[Int]] = waiting.values.flatten

  /** Whether a key's waiting items make a full batch, computed independently of the implementation. */
  private def full(l: Limits, items: Iterable[Item[Int]]): Boolean =
    items.size >= l.maxBatchSize || items.map(_.weight).sum >= l.maxBatchWeight

  private def run(l: Limits, ops: List[Op]): List[String] =
    ops
      .foldLeft((Lanes.empty[Int, Int](l), Model(), List.empty[String])) { case ((lanes, m, errors), op) =>
        val (next, model, found) = step(l, lanes, m, op)
        (next, model, errors ++ found ++ structural(l, next, model).map(e => s"after $op: $e"))
      }
      ._3

  private def step(l: Limits, lanes: Lanes[Int, Int], m: Model, op: Op): (Lanes[Int, Int], Model, List[String]) =
    op match
      case Op.Claim(key) =>
        val idle = l.maxBatchDelayNanos == 0 && !m.waiting.contains(key) && !m.busy(key)
        lanes.claim(key) match
          case Some(claimed) =>
            (claimed, m.copy(busy = m.busy + key), Option.unless(idle)(s"$op claimed a key that is not idle").toList)
          case None => (lanes, m, Option.when(idle)(s"$op refused an idle key").toList)

      case Op.Admit(key, weight, elapse) =>
        val now  = m.now + elapse
        val item = Item(m.serial, weight, now)
        val fits = m.items.size < l.maxQueueing && m.items.map(_.weight).sum + weight <= l.maxQueuedWeight
        lanes.admit(key, item) match
          case Some(admitted) =>
            val queue = m.waiting.getOrElse(key, Queue.empty).enqueue(item)
            val model = m.copy(now = now, serial = m.serial + 1, waiting = m.waiting.updated(key, queue))
            (admitted, model, Option.unless(fits)(s"$op admitted beyond the bounds").toList)
          case None =>
            (lanes, m.copy(now = now), Option.when(fits)(s"$op rejected although it fits").toList)

      case Op.Next(elapse) =>
        val now = m.now + elapse
        lanes.next(now) match
          case Right((key, batch, taken)) =>
            val queue          = m.waiting.getOrElse(key, Queue.empty)
            val (sent, remain) = queue.splitAt(batch.size)
            val sentWeight     = sent.map(_.weight).sum
            val wasFull        = full(l, queue)
            val errors         = List(
              Option.when(m.busy(key))(s"took key $key while its batch was in flight"),
              Option.when(batch.toList != sent.map(_.value).toList)(s"batch $batch is not the head of $queue"),
              Option.when(batch.size > l.maxBatchSize)(s"batch $batch exceeds maxBatchSize"),
              Option.when(batch.size > 1 && sentWeight > l.maxBatchWeight)(s"batch $batch exceeds maxBatchWeight"),
              Option.when(
                remain.nonEmpty && batch.size < l.maxBatchSize && sentWeight + remain.head.weight <= l.maxBatchWeight
              )(s"batch $batch left out ${remain.head} although it fits"),
              Option.when(!wasFull && queue.head.submittedAt + l.maxBatchDelayNanos > now)(
                s"partial batch $batch sent before its deadline"
              )
            ).flatten
            val waiting = if remain.isEmpty then m.waiting - key else m.waiting.updated(key, remain)
            (taken, m.copy(now = now, waiting = waiting, busy = m.busy + key), errors)
          case Left(deadline) =>
            val idle    = m.waiting.filter((key, _) => !m.busy(key))
            val due     = idle.filter((_, q) => full(l, q))
            val overdue = idle.filter((_, q) => q.head.submittedAt + l.maxBatchDelayNanos <= now)
            val next    = idle.values.map(_.head.submittedAt + l.maxBatchDelayNanos).minOption
            val errors  = List(
              Option.when(due.nonEmpty || overdue.nonEmpty)(s"nothing taken at $now although ${due ++ overdue} is due"),
              Option.when(deadline != next)(s"next deadline $deadline, expected $next")
            ).flatten
            (lanes, m.copy(now = now), errors)

      case Op.Drain =>
        val (items, drained) = lanes.drain
        val lost             = items.sorted != m.items.map(_.value).toList.sorted
        (drained, m.copy(waiting = Map.empty), Option.when(lost)(s"drain returned $items, expected ${m.items}").toList)

      case Op.Release(pick) =>
        m.busy.toList.sorted.lift(pick) match
          case Some(key) => (lanes.release(key), m.copy(busy = m.busy - key), Nil)
          case None      => (lanes.release(pick + 100), m, Nil) // releasing an unknown key changes nothing

  /** Invariants that must hold between any two operations. */
  private def structural(l: Limits, lanes: Lanes[Int, Int], m: Model): List[String] =
    val readyKeys   = lanes.ready.toList
    val waitingKeys = lanes.waiting.toList.flatMap((d, ks) => ks.map(_ -> d))
    val perLane     = lanes.lanes.toList.flatMap { (key, lane) =>
      List(
        Option
          .when(lane.items.toList != m.waiting.getOrElse(key, Queue.empty).toList)(s"lane $key holds the wrong items"),
        Option.when(lane.weight != lane.items.map(_.weight).sum)(s"lane $key has the wrong weight"),
        Option.when(lane.items.isEmpty && lane.status != Status.Busy)(s"empty idle lane $key was kept"),
        lane.status match
          case Status.Ready => Option.when(readyKeys.count(_ == key) != 1)(s"ready lane $key not in line once")
          case Status.Waiting(deadline) =>
            Option.when(
              waitingKeys != waitingKeys.distinct || !waitingKeys.contains(key -> deadline) || full(l, lane.items) ||
                deadline != lane.items.head.submittedAt + l.maxBatchDelayNanos
            )(s"waiting lane $key is misfiled")
          case Status.Busy => Option.unless(m.busy(key))(s"lane $key is busy without a batch in flight")
      ).flatten
    }
    perLane ++ List(
      Option.when(lanes.waiting.values.exists(_.isEmpty))("an empty deadline is left in the waiting index"),
      Option.when(m.waiting.exists((k, _) => !lanes.lanes.contains(k)))("a key with waiting items has no lane"),
      Option.when(m.busy.exists(k => !lanes.lanes.get(k).exists(_.status == Status.Busy)))("a busy key is not busy"),
      Option.when(readyKeys.exists(k => !lanes.lanes.get(k).exists(_.status == Status.Ready)))("ready line is stale"),
      Option.when(lanes.queued != m.items.size)(s"queued ${lanes.queued}, expected ${m.items.size}"),
      Option.when(lanes.weight != m.items.map(_.weight).sum)("total weight is wrong"),
      Option.when(lanes.busy != m.busy.size)(s"busy ${lanes.busy}, expected ${m.busy.size}")
    ).flatten

  private def limitsOf(size: Int, weight: Long = Long.MaxValue, delay: Long = 0) =
    Limits(size, weight, delay, maxQueueing = 100, maxQueuedWeight = Long.MaxValue)

  private def takeAll(lanes: Lanes[String, Int], now: Long): List[(String, List[Int])] =
    lanes.next(now) match
      case Right((key, batch, rest)) => (key -> batch.toList) :: takeAll(rest.release(key), now)
      case Left(_)                   => Nil

  def spec = suite("Lanes")(
    test("agrees with the model on every step of random admissions, takes and releases") {
      check(limits, Gen.listOfBounded(0, 60)(op))((l, ops) => assertTrue(run(l, ops) == Nil))
    } @@ Effort.samples(2000),
    test("serves ready keys in turn, so a key that always has more cannot starve the others") {
      val lanes = List("hot" -> 1, "hot" -> 2, "hot" -> 3, "hot" -> 4, "cold" -> 5, "hot" -> 6)
        .foldLeft(Lanes.empty[String, Int](limitsOf(size = 2))) { case (l, (key, n)) =>
          l.admit(key, Item(n, 1, 0)).get
        }
      assertTrue(
        takeAll(lanes, now = 0) == List("hot" -> List(1, 2), "cold" -> List(5), "hot" -> List(3, 4), "hot" -> List(6))
      )
    },
    test("never lets an enormous maxBatchDelay overflow into a deadline in the past") {
      val limits = Limits(10, Long.MaxValue, Long.MaxValue, maxQueueing = 10, maxQueuedWeight = Long.MaxValue)
      val lanes  = Lanes.empty[String, Int](limits).admit("k", Item(1, 1, submittedAt = 5)).get
      assertTrue(lanes.next(now = 10).left.toOption == Some(Some(Long.MaxValue)))
    },
    test("sends a lane that outgrew maxBatchSize while busy straight away, without waiting for the delay") {
      val limits = limitsOf(size = 2, delay = 1000)
      val first  = Lanes.empty[String, Int](limits).admit("k", Item(1, 1, 0)).get.admit("k", Item(2, 1, 0)).get
      val (_, _, inFlight) = first.next(0).toOption.get
      val piled            = (3 to 7).foldLeft(inFlight)((l, n) => l.admit("k", Item(n, 1, 0)).get)
      assertTrue(piled.release("k").next(now = 1).map(_._2.toList) == Right(List(3, 4)))
    },
    test("sends a single input heavier than maxBatchWeight on its own") {
      val lanes = Lanes
        .empty[String, Int](limitsOf(size = 10, weight = 5))
        .admit("k", Item(1, 9, 0))
        .get
        .admit("k", Item(2, 1, 0))
        .get
      assertTrue(takeAll(lanes, now = 0) == List("k" -> List(1), "k" -> List(2)))
    },
    test("claims only an idle key, and batches what arrives for it until it is released") {
      val claimed = Lanes.empty[String, Int](limitsOf(size = 10)).claim("k").get
      val more    = (1 to 3).foldLeft(claimed)((l, n) => l.admit("k", Item(n, 1, 0)).get)
      assertTrue(
        claimed.claim("k").isEmpty,
        more.next(0).isLeft,
        takeAll(more.release("k"), now = 0) == List("k" -> List(1, 2, 3)),
        more.release("k").claim("k").isEmpty, // still has inputs waiting
        Lanes.empty[String, Int](limitsOf(size = 10, delay = 1)).claim("k").isEmpty
      )
    },
    test("keeps a key's later inputs back while its batch is in flight, and sends them together afterwards") {
      val first            = Lanes.empty[String, Int](limitsOf(size = 10)).admit("k", Item(1, 1, 0)).get
      val (_, _, inFlight) = first.next(0).toOption.get
      val more             = (2 to 4).foldLeft(inFlight)((l, n) => l.admit("k", Item(n, 1, 0)).get)
      assertTrue(more.next(0).isLeft, takeAll(more.release("k"), now = 0) == List("k" -> List(2, 3, 4)))
    }
  ) @@ Effort.timeout(5.minutes)
