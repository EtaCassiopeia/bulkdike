package ferry

import zio.*
import zio.test.*

/**
 * Compares a real Ferry, step by step, with an executable specification of its batching rules.
 *
 * Batch calls here complete instantly, so the only things deciding batch composition and timing are arrivals and the
 * passage of time. Each step settles the TestClock before the next, which makes the run deterministic and lets the
 * model predict every batch, its contents in order, and the instant it is dispatched.
 */
object FerryModelSpec extends ZIOSpecDefault:

  enum Op:
    case Submit
    case Advance(ms: Int)

  /** The batching rules, written as plainly as possible. Times are milliseconds since the run started. */
  final case class Model(
    maxBatchSize: Int,
    maxBatchDelayMs: Long,
    now: Long = 0,
    nextId: Int = 0,
    assembling: Vector[(Int, Long)] = Vector.empty,
    dispatched: Vector[(Vector[Int], Long)] = Vector.empty
  ):
    def step(op: Op): Model = op match
      case Op.Submit =>
        val batch = assembling :+ (nextId -> now)
        val next  = copy(nextId = nextId + 1)
        if batch.size >= maxBatchSize || maxBatchDelayMs == 0 then next.dispatch(batch, now)
        else next.copy(assembling = batch)
      case Op.Advance(ms) =>
        val end = now + ms
        assembling.headOption match
          case Some((_, oldest)) if oldest + maxBatchDelayMs <= end =>
            dispatch(assembling, oldest + maxBatchDelayMs).copy(now = end)
          case _ => copy(now = end)

    private def dispatch(batch: Vector[(Int, Long)], at: Long): Model =
      copy(assembling = Vector.empty, dispatched = dispatched :+ (batch.map(_._1) -> at))

  private val runs =
    for
      maxBatchSize       <- Gen.int(1, 5)
      maxBatchDelayMs    <- Gen.elements(0, 1, 100, 1000)
      maxInFlightBatches <- Gen.int(1, 3)
      advance = Gen.oneOf(Gen.int(0, 1500), Gen.elements(maxBatchDelayMs, (maxBatchDelayMs - 1).max(0)))
      ops <- Gen.listOfBounded(0, 40)(
        Gen.weighted(Gen.const(Op.Submit) -> 3.0, advance.map(Op.Advance(_)) -> 2.0)
      )
    yield (maxBatchSize, maxBatchDelayMs, maxInFlightBatches, ops)

  private def runReal(maxBatchSize: Int, maxBatchDelayMs: Int, maxInFlightBatches: Int, ops: List[Op]) =
    ZIO.scoped {
      for
        cfg <- ZIO
          .fromEither(FerryConfig.make(maxBatchSize, maxBatchDelayMs.millis, maxInFlightBatches, 64))
          .orDie
        start <- Clock.nanoTime // the TestClock keeps running across samples
        log   <- Ref.make(Vector.empty[(Vector[Int], Long)])
        ferry <- Ferry.make(cfg) { (ids: NonEmptyChunk[Int]) =>
          Clock.nanoTime.flatMap(t => log.update(_ :+ (ids.toVector -> (t - start) / 1_000_000)))
        }((id, _) => id)
        _ <- ZIO.foldLeft(ops)(0) {
          case (id, Op.Submit)      => ferry(id).fork *> TestClock.adjust(Duration.Zero).as(id + 1)
          case (id, Op.Advance(ms)) => TestClock.adjust(ms.millis).as(id)
        }
        out <- log.get
      yield out
    }

  def spec = suite("Ferry against its model")(
    test("dispatches exactly the batches the model predicts, in order, at the predicted instants") {
      check(runs) { (maxBatchSize, maxBatchDelayMs, maxInFlightBatches, ops) =>
        val expected = ops.foldLeft(Model(maxBatchSize, maxBatchDelayMs.toLong))(_.step(_)).dispatched
        runReal(maxBatchSize, maxBatchDelayMs, maxInFlightBatches, ops).map(actual => assertTrue(actual == expected))
      }
    } @@ Effort.samples(200)
  ) @@ Effort.timeout(10.minutes)
