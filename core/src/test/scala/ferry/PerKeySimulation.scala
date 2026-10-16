package ferry

import zio.*
import zio.test.*

/**
 * Random workloads for `Ferry.perKey`, run on the TestClock, with every trace checked against its contract.
 *
 * A scenario submits inputs for a few keys at random instants, with random weights, batch latencies, failing batches,
 * callers that give up, and optionally closes the scope part way through. Time only moves when the simulation advances
 * the TestClock, so delays and deadlines are exact.
 */
object PerKeySimulation:

  /** `rank` is the input's place in its key's `Order.by`; all zero in a scenario that keeps arrival order. */
  final case class Input(id: Int, key: Int, weight: Long, rank: Long)
  final case class Submission(input: Input, gapMs: Long, cancelAfterMs: Option[Long])

  final case class Scenario(
    config: PerKeyConfig,
    weighted: Boolean,
    ranked: Boolean,
    submissions: List[Submission],
    latenciesMs: Chunk[Long],
    failEvery: Int,
    closeAfterMs: Option[Long]
  ):
    def cancellations: Boolean = submissions.exists(_.cancelAfterMs.isDefined)

  enum Outcome:
    case Succeeded(value: Int)
    case Rejected, Failed, Interrupted

  /** `caller` is the id of the input whose caller made the call directly, if it was not made by a worker. */
  final case class Call(
    serial: Int,
    key: Int,
    inputs: Chunk[Input],
    startMs: Long,
    failed: Boolean,
    caller: Option[Int]
  )

  final case class Trace(
    outcomes: Map[Int, Outcome],
    submittedAtMs: Map[Int, Long],
    cancelledAtMs: Map[Int, Long],
    calls: Chunk[Call],
    peak: Int,
    overlapping: Set[Int],
    closedAtMs: Option[Long],
    hung: Set[Int]
  )

  def scenario(cancellations: Boolean, close: Boolean): Gen[Any, Scenario] =
    for
      size     <- Gen.int(1, 4)
      inFlight <- Gen.int(1, 3)
      delayMs  <- Gen.oneOf(Gen.const(0L), Gen.long(1, 6).map(_ * 5))
      weighted <- Gen.boolean
      maxW     <- if weighted then Gen.long(3, 10) else Gen.const(Long.MaxValue)
      count    <- Gen.int(1, 40)
      queueing <- Gen.int(size, 40)
      queuedW  <- if weighted then Gen.long(maxW, 60) else Gen.const(Long.MaxValue)
      direct   <- Gen.boolean
      ranked   <- Gen.boolean
      config = PerKeyConfig.make(size, inFlight, queueing, delayMs.millis, maxW, queuedW, direct).toOption.get
      submissions <- Gen.listOfN(count)(
        Gen.int(0, 3) <*> Gen.long(1, 6) <*> (if ranked then Gen.long(0, 3) else Gen.const(0L)) <*>
          Gen.long(0, 3).map(_ * 5) <*> (if cancellations then Gen.option(Gen.long(0, 8).map(_ * 5)) else Gen.none)
      )
      latencies <- Gen.chunkOfBounded(1, 8)(Gen.long(0, 8).map(_ * 5))
      failEvery <- Gen.oneOf(Gen.int(2, 6), Gen.const(Int.MaxValue))
      closeAt   <- if close then Gen.long(0, 80).map(n => Some(n * 5)) else Gen.none
    yield Scenario(
      config,
      weighted,
      ranked,
      submissions.zipWithIndex.map { case ((key, weight, rank, gap, cancel), id) =>
        Submission(Input(id, key, if weighted then weight else 1L, rank), gap, cancel)
      },
      latencies,
      failEvery,
      closeAt
    )

  private val nowMs: UIO[Long] = Clock.currentTime(java.util.concurrent.TimeUnit.MILLISECONDS)

  def run(s: Scenario): UIO[Trace] = ZIO.scoped {
    for
      scope     <- Scope.make
      clients   <- Scope.make // callers outlive the fiber that starts them, so they get a scope of their own
      calls     <- Ref.make(Chunk.empty[Call])
      serials   <- Ref.make(0)
      current   <- Ref.make(0)
      peak      <- Ref.make(0)
      perKey    <- Ref.make(Map.empty[Int, Int])
      overlap   <- Ref.make(Set.empty[Int])
      outcomes  <- Ref.make(Map.empty[Int, Outcome])
      submitted <- Ref.make(Map.empty[Int, Long])
      cancelled <- Ref.make(Map.empty[Int, Long])
      closedAt  <- Ref.make(Option.empty[Long])
      // Set by each client, so a call made by a client rather than a worker (forked at creation) can tell who made it.
      caller <- FiberRef.make(Option.empty[Int])
      ferry  <- scope.extend(
        Ferry.perKey(s.config, (_: Input).key, (_: Input).weight, Order.by((_: Input).rank)) {
          (key, inputs: NonEmptyChunk[Input]) =>
            // Bookkeeping is acquired and released as one: a direct call is interrupted whenever its caller is, which
            // must not leave a key counted as busy.
            val enter =
              for
                n      <- serials.getAndUpdate(_ + 1)
                start  <- nowMs
                direct <- caller.get
                // Only workers' batches count against maxInFlightBatches.
                _    <- current.updateAndGet(_ + 1).flatMap(c => peak.update(_ max c)).when(direct.isEmpty)
                busy <- perKey.modify(m => m.getOrElse(key, 0) -> m.updated(key, m.getOrElse(key, 0) + 1))
                _    <- overlap.update(_ + key).when(busy > 0)
                fails = n % s.failEvery == s.failEvery - 1
                _ <- calls.update(_ :+ Call(n, key, inputs.toChunk, start, fails, direct))
              yield (n, fails, direct.isEmpty)
            ZIO.acquireReleaseWith(enter) { (_, _, worker) =>
              current.update(_ - 1).when(worker) *> perKey.update(m => m.updated(key, m(key) - 1))
            } { (n, fails, _) =>
              ZIO.sleep(s.latenciesMs(n % s.latenciesMs.size).millis) *>
                ZIO.fail("unavailable").when(fails).as(inputs.map(i => i.id -> i.id * 10).toMap)
            }
        }((input, results) => results(input.id))
      )
      classify = (exit: Exit[FerryError[String], Int]) =>
        exit match
          case Exit.Success(v)                                                  => Outcome.Succeeded(v)
          case Exit.Failure(c) if c.failureOption.contains(FerryError.Rejected) => Outcome.Rejected
          case Exit.Failure(c) if c.failureOption.isDefined                     => Outcome.Failed
          case Exit.Failure(_)                                                  => Outcome.Interrupted
      client = (sub: Submission) =>
        for
          at    <- nowMs
          _     <- submitted.update(_ + (sub.input.id -> at))
          fiber <- caller.locally(Some(sub.input.id))(ferry(sub.input)).forkIn(clients)
          // Awaited from outside: a caller interrupted before it even started still has an outcome.
          _ <- fiber.await.flatMap(e => outcomes.update(_ + (sub.input.id -> classify(e)))).forkIn(clients)
          _ <- ZIO.foreachDiscard(sub.cancelAfterMs) { after =>
            (ZIO.sleep(after.millis) *> nowMs.flatMap(t => cancelled.update(_ + (sub.input.id -> t))) *>
              fiber.interrupt).forkIn(clients)
          }
        yield ()
      _ <- ZIO.foreachDiscard(s.submissions)(sub => ZIO.sleep(sub.gapMs.millis) *> client(sub)).fork
      _ <- ZIO.foreachDiscard(s.closeAfterMs) { after =>
        (ZIO.sleep(after.millis) *> nowMs.flatMap(t => closedAt.set(Some(t))) *> scope.close(Exit.unit)).fork
      }
      _     <- VirtualTime.settle(outcomes.get.map(_.size == s.submissions.size))
      done  <- outcomes.get
      _     <- clients.close(Exit.unit) *> scope.close(Exit.unit)
      trace <- calls.get <*> peak.get <*> overlap.get <*> submitted.get <*> cancelled.get <*> closedAt.get
    yield
      val (cs, pk, ov, sub, can, closed) = trace
      Trace(done, sub, can, cs, pk, ov, closed, s.submissions.map(_.input.id).toSet -- done.keySet)
  }

  def violations(s: Scenario, t: Trace): List[String] =
    val cfg     = s.config
    val sent    = t.calls.flatMap(c => c.inputs.map(_ -> c))
    val sentIds = sent.map(_._1.id)
    val byKey   = t.calls.sortBy(_.serial).groupBy(_.key)
    val total   = s.submissions.size
    val weight  = s.submissions.map(_.input.weight).sum

    def outcomeFits(input: Input): Option[String] =
      val outcome = t.outcomes.get(input.id)
      sent.find(_._1 == input).map(_._2) match
        case Some(call) if call.failed =>
          Option.unless(outcome.exists(o => o == Outcome.Failed || o == Outcome.Interrupted))(
            s"input ${input.id} of failed call ${call.serial} ended $outcome"
          )
        case Some(_) =>
          Option.unless(outcome.exists(o => o == Outcome.Succeeded(input.id * 10) || o == Outcome.Interrupted))(
            s"input ${input.id} of a successful call ended $outcome"
          )
        case None =>
          Option.unless(outcome.exists(o => o == Outcome.Rejected || o == Outcome.Interrupted))(
            s"input ${input.id} was never sent but ended $outcome"
          )

    List(
      Option.when(t.hung.nonEmpty)(s"callers never answered: ${t.hung}"),
      Option.when(t.overlapping.nonEmpty)(s"keys with two batches in flight at once: ${t.overlapping}"),
      Option.when(t.peak > cfg.maxInFlightBatches)(s"${t.peak} batches in flight, max ${cfg.maxInFlightBatches}"),
      Option.when(sentIds.distinct.size != sentIds.size)("an input was sent twice"),
      Option.when(
        total <= cfg.maxQueueing && weight <= cfg.maxQueuedWeight && t.outcomes.values.exists(_ == Outcome.Rejected)
      )(
        "rejected although everything fits in the queue"
      )
    ).flatten ++
      // An input must not be overtaken by one sent before it unless it arrived after that one was taken (a late low rank
      // goes in the next batch). Times are whole milliseconds, so only strictly earlier arrivals count as waiting.
      byKey.toList.flatMap { (key, calls) =>
        val sentInOrder = calls.flatMap(c => c.inputs.map(_ -> c.startMs))
        val overtaken   = sentInOrder.zipWithIndex.flatMap { case ((a, takenAt), i) =>
          sentInOrder.drop(i + 1).collect {
            case (b, _)
                if t.submittedAtMs(b.id) < takenAt && (b.rank < a.rank ||
                  b.rank == a.rank && t.submittedAtMs(b.id) < t.submittedAtMs(a.id)) =>
              s"${b.id} after ${a.id}"
          }
        }
        Option.when(overtaken.nonEmpty)(s"key $key sent out of order: $overtaken in ${calls.map(_.inputs.map(_.id))}")
      } ++
      t.calls.toList.flatMap { c =>
        val w = c.inputs.map(_.weight).sum
        List(
          Option.when(c.inputs.exists(_.key != c.key))(s"call ${c.serial} mixes keys"),
          Option.when(c.caller.isDefined && c.inputs.map(i => Some(i.id)) != Chunk(c.caller))(
            s"direct call ${c.serial} carries ${c.inputs.map(_.id)}, not just its caller's input ${c.caller}"
          ),
          Option.when(c.caller.isDefined && !cfg.maxBatchDelay.isZero)(
            s"direct call ${c.serial} although batches are delayed"
          ),
          Option.when(c.inputs.size > cfg.maxBatchSize)(s"call ${c.serial} exceeds maxBatchSize"),
          Option.when(c.inputs.size > 1 && w > cfg.maxBatchWeight)(s"call ${c.serial} exceeds maxBatchWeight"),
          Option.when(t.closedAtMs.exists(c.startMs > _))(s"call ${c.serial} started after close"),
          Option.when(
            !s.cancellations && !s.weighted && c.inputs.size < cfg.maxBatchSize &&
              c.inputs.map(i => t.submittedAtMs(i.id)).min + cfg.maxBatchDelay.toMillis > c.startMs
          )(s"partial call ${c.serial} sent before its deadline"),
          c.inputs
            .find(i =>
              t.cancelledAtMs.get(i.id).exists(_ < c.startMs) && t.outcomes.get(i.id).contains(Outcome.Interrupted)
            )
            .map(i => s"input ${i.id} sent in call ${c.serial} after its caller gave up")
        ).flatten
      } ++
      s.submissions.flatMap(sub => outcomeFits(sub.input)) ++
      s.submissions.flatMap { sub =>
        Option.when(
          t.closedAtMs.exists(t.submittedAtMs.get(sub.input.id).getOrElse(-1L) > _) &&
            !t.outcomes.get(sub.input.id).contains(Outcome.Interrupted)
        )(s"input ${sub.input.id} submitted after close was not interrupted")
      }
