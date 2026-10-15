package bulkdike

import zio.*
import zio.test.*

/**
 * Generates random workloads and runs them against a real BulkDike on the TestClock.
 *
 * `TestClock.adjust` waits until every fiber of the test is suspended before it advances, and fires sleeps in timestamp
 * order, so virtual time acts as a deterministic scheduler: what is concurrent is only what happens at the same
 * instant. The resulting [[Simulation.Trace]] records everything observable, and [[Simulation.violations]] checks it
 * against BulkDike's contract.
 */
object Simulation:

  enum Behaviour:
    case Succeed(latencyMs: Int)
    case Fail(latencyMs: Int)
    case Die(latencyMs: Int)

    def latency: Duration = this match
      case Succeed(ms) => ms.millis
      case Fail(ms)    => ms.millis
      case Die(ms)     => ms.millis

  /** A caller that arrives `gapMs` after the previous one and is interrupted `cancelAfterMs` after it arrived. */
  final case class Submission(gapMs: Int, cancelAfterMs: Option[Int])

  final case class Scenario(
    maxBatchSize: Int,
    maxBatchDelayMs: Int,
    maxInFlightBatches: Int,
    maxQueueing: Int,
    submissions: Chunk[Submission],
    behaviours: NonEmptyChunk[Behaviour],
    closeAfter: Option[Int]
  ):
    def config: UIO[BulkDikeConfig] =
      ZIO.fromEither(BulkDikeConfig.make(maxBatchSize, maxBatchDelayMs.millis, maxInFlightBatches, maxQueueing)).orDie

    def behaviourOf(batchIndex: Int): Behaviour = behaviours(batchIndex % behaviours.size)

    def hasCancellations: Boolean = submissions.exists(_.cancelAfterMs.isDefined)

  final case class Boom(batch: Int) extends RuntimeException(s"boom in batch $batch")

  final case class Batch(index: Int, ids: Chunk[Int], startedAt: Long, behaviour: Behaviour)

  final case class Trace(
    batches: Chunk[Batch],
    outcomes: Map[Int, Exit[BulkDikeError[String], Int]],
    submittedAt: Map[Int, Long],
    cancelledAt: Map[Int, Long],
    peakInFlight: Int,
    hung: Set[Int],
    batchesStartedAfterClose: Int,
    submittedAfterClose: Set[Int]
  )

  // Values chosen so that arrivals, deadlines and completions regularly coincide, which is where races live.
  private val delays    = Gen.elements(0, 1, 500, 2000)
  private val latencies = Gen.oneOf(Gen.elements(0, 500, 2000), Gen.int(1, 2500))

  private val behaviour: Gen[Any, Behaviour] =
    latencies.flatMap { ms =>
      Gen.weighted(
        Gen.const(Behaviour.Succeed(ms)) -> 6.0,
        Gen.const(Behaviour.Fail(ms))    -> 2.0,
        Gen.const(Behaviour.Die(ms))     -> 1.0
      )
    }

  private def submission(cancellations: Boolean): Gen[Any, Submission] =
    val gap    = Gen.oneOf(Gen.const(0), Gen.elements(1, 500, 2000), Gen.int(0, 3000))
    val cancel =
      if cancellations then Gen.weighted(Gen.none -> 4.0, Gen.some(Gen.oneOf(Gen.const(0), Gen.int(0, 3000))) -> 1.0)
      else Gen.none
    (gap <*> cancel).map(Submission.apply)

  def scenario(cancellations: Boolean, close: Boolean): Gen[Any, Scenario] =
    for
      maxBatchSize       <- Gen.int(1, 6)
      maxBatchDelayMs    <- delays
      maxInFlightBatches <- Gen.int(1, 3)
      maxQueueing        <- Gen.int(maxBatchSize, maxBatchSize + 6)
      submissions        <- Gen.chunkOfBounded(0, 40)(submission(cancellations))
      first              <- behaviour
      rest               <- Gen.chunkOfBounded(0, 4)(behaviour)
      closeAfter         <- if close then Gen.int(0, submissions.size).map(Some(_)) else Gen.const(None)
    yield Scenario(
      maxBatchSize,
      maxBatchDelayMs,
      maxInFlightBatches,
      maxQueueing,
      submissions,
      NonEmptyChunk.fromIterable(first, rest),
      closeAfter
    )

  private type Caller = Fiber.Runtime[BulkDikeError[String], Int]

  def run(s: Scenario): UIO[Trace] =
    for
      cfg       <- s.config
      batches   <- Ref.make(Chunk.empty[Batch])
      active    <- Ref.make(0)
      peak      <- Ref.make(0)
      closed    <- Ref.make(false)
      late      <- Ref.make(0)
      callers   <- Ref.make(Map.empty[Int, Caller])
      submitted <- Ref.make(Map.empty[Int, Long])
      cancelled <- Ref.make(Map.empty[Int, Long])

      call = (ids: NonEmptyChunk[Int]) =>
               for
                 now   <- Clock.nanoTime
                 _     <- late.update(_ + 1).whenZIO(closed.get)
                 batch <- batches.modify { bs =>
                            val b = Batch(bs.size, ids.toChunk, now, s.behaviourOf(bs.size))
                            (b, bs :+ b)
                          }
                 track  = active.updateAndGet(_ + 1).flatMap(n => peak.update(_ max n))
                 result <- ZIO.acquireReleaseWith(track)(_ => active.update(_ - 1)) { _ =>
                             ZIO.sleep(batch.behaviour.latency) *> (batch.behaviour match
                               case Behaviour.Succeed(_) => ZIO.succeed(ids.map(id => id -> id * 2).toMap)
                               case Behaviour.Fail(_)    => ZIO.fail(s"batch-${batch.index}")
                               case Behaviour.Die(_)     => ZIO.die(Boom(batch.index))
                             )
                           }
               yield result

      submit = (dike: BulkDike[Int, String, Int], sub: Submission, id: Int) =>
                 for
                   _      <- TestClock.adjust(sub.gapMs.millis).when(sub.gapMs > 0)
                   now    <- Clock.nanoTime
                   _      <- submitted.update(_ + (id -> now))
                   caller <- dike(id).fork
                   _      <- callers.update(_ + (id -> caller))
                   _      <- ZIO.foreachDiscard(sub.cancelAfterMs) { ms =>
                               (ZIO.sleep(ms.millis) *> caller.interrupt.flatMap { exit =>
                                 Clock.nanoTime.flatMap(at => cancelled.update(_ + (id -> at))).when(exit.isInterrupted)
                               }).fork
                             }
                 yield ()

      (before, after) = s.closeAfter.fold((s.submissions, Chunk.empty[Submission]))(s.submissions.splitAt)

      dike <- ZIO.scoped {
                for
                  dike <- BulkDike.make(cfg)(call)((id, results: Map[Int, Int]) => results(id))
                  _    <- ZIO.foreachDiscard(before.zipWithIndex)((sub, id) => submit(dike, sub, id))
                  _    <- settleBounded(callers).when(s.closeAfter.isEmpty)
                yield dike
              }
      _    <- closed.set(true)
      _    <- ZIO.foreachDiscard(after.zipWithIndex)((sub, i) => submit(dike, sub, before.size + i))
      _    <- settleBounded(callers)

      finished <- callers.get.flatMap(cs => ZIO.foreach(cs.toList)((id, f) => f.poll.map(id -> _)))
      trace    <- (batches.get <*> submitted.get <*> cancelled.get <*> peak.get <*> late.get).map {
                    (bs, sub, canc, pk, lt) =>
                      Trace(
                        batches = bs,
                        outcomes = finished.collect { case (id, Some(exit)) => id -> exit }.toMap,
                        submittedAt = sub,
                        cancelledAt = canc,
                        peakInFlight = pk,
                        hung = finished.collect { case (id, None) => id }.toSet,
                        batchesStartedAfterClose = lt,
                        submittedAfterClose = after.indices.map(before.size + _).toSet
                      )
                  }
    yield trace

  /**
   * Advances virtual time until every caller has finished, giving up after an hour of virtual time. Large steps lose no
   * precision: `adjust` still fires every sleep at its own instant.
   */
  private def settleBounded(callers: Ref[Map[Int, Caller]]): UIO[Unit] =
    val allDone = callers.get.flatMap(cs => ZIO.forall(cs.values)(_.poll.map(_.isDefined)))
    (TestClock.adjust(10.seconds) *> allDone).repeat(Schedule.recurUntil[Boolean](identity) && Schedule.recurs(360)).unit

  def isRejected(exit: Exit[BulkDikeError[?], ?]): Boolean =
    exit.causeOption.flatMap(_.failureOption).contains(BulkDikeError.Rejected)

  /** Every way in which `trace` breaks BulkDike's contract for scenario `s`; empty when it is honoured. */
  def violations(s: Scenario, t: Trace): List[String] =
    val batchOf       = t.batches.flatMap(b => b.ids.map(_ -> b)).toMap
    val sentTwice     = t.batches.flatMap(_.ids).groupBy(identity).collect { case (id, n) if n.size > 1 => id }
    val maxDelayNanos = s.maxBatchDelayMs.millis.toNanos
    val closing       = s.closeAfter.isDefined

    def outcome(id: Int, exit: Exit[BulkDikeError[String], Int]): Option[String] =
      val batch = batchOf.get(id)
      exit match
        case Exit.Success(v) =>
          Option.when(!batch.exists(_.behaviour.isInstanceOf[Behaviour.Succeed]) || v != id * 2)(
            s"caller $id got $v but its batch was $batch"
          )
        case Exit.Failure(cause) if cause.isInterruptedOnly =>
          Option.when(!closing && !t.cancelledAt.contains(id))(s"caller $id was interrupted but nobody interrupted it")
        case Exit.Failure(cause) =>
          (cause.failureOption, cause.dieOption) match
            case (Some(BulkDikeError.Rejected), _) =>
              batch.map(b => s"caller $id was rejected but its input was sent in batch ${b.index}")
            case (Some(BulkDikeError.Failed(e)), _) =>
              Option.when(!batch.exists(b => b.behaviour.isInstanceOf[Behaviour.Fail] && e == s"batch-${b.index}"))(
                s"caller $id failed with $e but its batch was $batch"
              )
            case (_, Some(Boom(index))) =>
              Option.when(!batch.exists(b => b.behaviour.isInstanceOf[Behaviour.Die] && b.index == index))(
                s"caller $id died in batch $index but its batch was $batch"
              )
            case _ => Some(s"caller $id ended with unexpected cause $cause")

    List(
      Option.when(t.hung.nonEmpty)(s"callers never completed: ${t.hung.toList.sorted}"),
      Option.when(sentTwice.nonEmpty)(s"inputs sent in more than one batch: $sentTwice"),
      Option.when(t.peakInFlight > s.maxInFlightBatches)(
        s"${t.peakInFlight} batches ran at once, limit is ${s.maxInFlightBatches}"
      ),
      Option.when(t.batchesStartedAfterClose > 0)(s"${t.batchesStartedAfterClose} batches started after close"),
      Option.when(s.submissions.size <= s.maxQueueing && t.outcomes.values.exists(isRejected))(
        "rejected a call although the queue could never have been full"
      )
    ).flatten ++
      t.batches.collect {
        case b if b.ids.isEmpty || b.ids.size > s.maxBatchSize =>
          s"batch ${b.index} has ${b.ids.size} inputs, limit is ${s.maxBatchSize}"
      } ++
      // A partial batch must not leave before its deadline. Interruptions can legitimately shrink a full batch.
      t.batches.collect {
        case b
            if !s.hasCancellations && b.ids.size < s.maxBatchSize &&
              b.startedAt < b.ids.map(t.submittedAt).min + maxDelayNanos =>
          s"partial batch ${b.index} ${b.ids} left at ${b.startedAt}ns, before its deadline"
      } ++
      // A caller interrupted strictly before a batch started is never part of it.
      t.cancelledAt.toList.flatMap { (id, at) =>
        batchOf.get(id).filter(_.startedAt > at).map(b => s"caller $id was interrupted at $at but sent in batch $b")
      } ++
      t.outcomes.toList.flatMap(outcome) ++
      t.submittedAfterClose.toList.flatMap { id =>
        t.outcomes.get(id).filterNot(_.isInterrupted).map(exit => s"call $id made after close ended with $exit")
      }
