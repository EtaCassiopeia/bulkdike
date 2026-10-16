package ferry

import zio.*
import zio.stm.*

/** The implementation of [[Ferry.perKey]]. */
private[ferry] object PerKeyFerry:

  final private case class Entry[I, E, A](input: I, result: Promise[FerryError[E], A])

  private enum Admission:
    case Direct, Admitted, Full, Closed

  def make[R, E, K, I, B, A](config: PerKeyConfig, key: I => K, weigh: I => Long, order: Order[I])(
    call: (K, NonEmptyChunk[I]) => ZIO[R, E, B]
  )(extract: (I, B) => A): URIO[R & Scope, Ferry[I, E, A]] =
    val limits = Lanes.Limits(
      maxBatchSize = config.maxBatchSize,
      maxBatchWeight = config.maxBatchWeight,
      maxBatchDelayNanos = config.maxBatchDelay.toNanos,
      maxQueueing = config.maxQueueing,
      maxQueuedWeight = config.maxQueuedWeight,
      directIdleKeys = config.directIdleKeys
    )
    for
      lanes <- TRef.make(Lanes.empty[K, Entry[I, E, A]](limits)).commit
      open  <- TRef.make(true).commit
      env   <- ZIO.environment[R]
      scope <- ZIO.scope
      ferry = Live(
        key,
        weigh,
        order,
        (k, inputs) => call(k, inputs).provideEnvironment(env),
        extract,
        lanes,
        open,
        scope
      )
      // Finalizers run in reverse order: workers are interrupted first, then whatever is still waiting is released.
      _ <- ZIO.addFinalizer(ferry.close)
      _ <- ZIO.foreachDiscard(1 to config.maxInFlightBatches)(_ => ferry.worker.forkScoped)
    yield ferry

  /**
   * All state lives in one `TRef`, and every change to it is one STM transaction: admission is atomic with `close`, and
   * taking a batch is atomic with marking its key busy, so neither can interleave with anything else.
   */
  final private class Live[E, K, I, B, A](
    key: I => K,
    weigh: I => Long,
    order: Order[I],
    call: (K, NonEmptyChunk[I]) => IO[E, B],
    extract: (I, B) => A,
    lanes: TRef[Lanes[K, Entry[I, E, A]]],
    open: TRef[Boolean],
    scope: Scope
  ) extends Ferry[I, E, A]:

    override def apply(input: I): IO[FerryError[E], A] =
      ZIO.uninterruptibleMask { restore =>
        for
          // The application's functions run once, here, and never inside a transaction, which may retry.
          k           <- ZIO.succeed(key(input))
          rank        <- ZIO.succeed(order.rank(input))
          weight      <- ZIO.succeed(weigh(input) max 0L)
          result      <- Promise.make[FerryError[E], A]
          submittedAt <- Clock.nanoTime
          item = Lanes.Item(Entry(input, result), weight, submittedAt, rank)
          a <- admit(k, item).flatMap {
            case Admission.Direct   => direct(k, input, restore)
            case Admission.Admitted => restore(result.await).onInterrupt(result.interrupt)
            case Admission.Full     => ZIO.fail(FerryError.Rejected)
            case Admission.Closed   => ZIO.interrupt
          }
        yield a
      }

    /**
     * Sends an input of an idle key at once, as a batch of one, without a queue or a worker. The call runs in the
     * ferry's scope, so closing the scope interrupts it like any batch in flight; the key is released only once the
     * call is over.
     */
    private def direct(k: K, input: I, restore: ZIO.InterruptibilityRestorer): IO[FerryError[E], A] =
      call(k, NonEmptyChunk.single(input))
        .mapBoth(FerryError.Failed(_), extract(input, _))
        .interruptible // forked from an uninterruptible region, whose status it would otherwise inherit
        .forkIn(scope)
        .flatMap(fiber => restore(fiber.join).onInterrupt(fiber.interrupt))
        .ensuring(release(k))

    private def release(k: K): UIO[Unit] = lanes.update(_.release(k)).commit

    override def metrics: UIO[Ferry.Metrics] =
      lanes.get.map(l => Ferry.Metrics(l.queued, l.busy)).commit

    /**
     * Claims an idle key for a direct call, or else joins the key's lane, in one transaction with the check for close.
     */
    private def admit(k: K, item: Lanes.Item[Entry[I, E, A]]): UIO[Admission] =
      open.get.flatMap { isOpen =>
        if !isOpen then STM.succeed(Admission.Closed)
        else
          lanes.modify { l =>
            l.claim(k)
              .map(Admission.Direct -> _)
              .orElse(l.admit(k, item).map(Admission.Admitted -> _))
              .getOrElse(Admission.Full -> l)
          }
      }.commit

    def close: UIO[Unit] =
      (open.set(false) *> lanes.modify(_.drain)).commit.flatMap(ZIO.foreachDiscard(_)(_.result.interrupt))

    /** Takes and runs one batch at a time, forever. One worker runs per batch slot. */
    def worker: UIO[Nothing] =
      Ref
        .make(Chunk.empty[Entry[I, E, A]])
        .flatMap { taken =>
          take(taken)
            .flatMap((k, batch) => run(k, batch).ensuring(release(k)))
            // An entry taken out of its lane is owned by this worker until its result is set; never strand a caller.
            .onInterrupt(taken.get.flatMap(ZIO.foreachDiscard(_)(_.result.interrupt)))
        }
        .forever

    private def take(taken: Ref[Chunk[Entry[I, E, A]]]): UIO[(K, NonEmptyChunk[Entry[I, E, A]])] =
      Clock.nanoTime.flatMap { now =>
        lanes
          .modify { l =>
            l.next(now) match
              case Right((k, batch, rest)) => Right(k -> batch)   -> rest
              case Left(deadline)          => Left(deadline -> l) -> l
          }
          .commit
          // Taking a batch and recording it happen together, or an interruption in between would strand its callers.
          .tap {
            case Right((_, batch)) => taken.set(batch.toChunk)
            case Left(_)           => ZIO.unit
          }
          .uninterruptible
          .flatMap {
            case Right(batch)           => ZIO.succeed(batch)
            case Left((deadline, seen)) =>
              // Sleep until the lanes change or the earliest waiting lane falls due, whichever comes first.
              val changed = lanes.get.retryUntil(_ ne seen).commit.unit
              deadline.fold(changed)(d => changed.timeout(Duration.fromNanos(d - now)).unit) *> take(taken)
          }
      }

    private def run(k: K, batch: NonEmptyChunk[Entry[I, E, A]]): UIO[Unit] =
      // Callers interrupted while their input was waiting have already completed their promise.
      ZIO.filterNot(batch)(_.result.isDone).map(NonEmptyChunk.fromChunk).flatMap {
        case None          => ZIO.unit
        case Some(entries) =>
          call(k, entries.map(_.input)).exit.flatMap {
            case Exit.Success(b) =>
              ZIO.foreachDiscard(entries)(entry => entry.result.complete(ZIO.succeed(extract(entry.input, b))))
            case Exit.Failure(cause) =>
              ZIO.foreachDiscard(entries)(_.result.failCause(cause.map(FerryError.Failed(_))))
          }
      }
