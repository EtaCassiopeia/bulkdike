package bulkdike

import zio.*
import zio.stm.*

/** The implementation of [[BulkDike.perKey]]. */
private[bulkdike] object PerKeyDike:

  final private case class Entry[I, E, A](input: I, result: Promise[BulkDikeError[E], A])

  private enum Admission:
    case Admitted, Full, Closed

  def make[R, E, K, I, B, A](config: PerKeyConfig, key: I => K, weigh: I => Long)(
    call: (K, NonEmptyChunk[I]) => ZIO[R, E, B]
  )(extract: (I, B) => A): URIO[R & Scope, BulkDike[I, E, A]] =
    val limits = Lanes.Limits(
      maxBatchSize = config.maxBatchSize,
      maxBatchWeight = config.maxBatchWeight,
      maxBatchDelayNanos = config.maxBatchDelay.toNanos,
      maxQueueing = config.maxQueueing,
      maxQueuedWeight = config.maxQueuedWeight
    )
    for
      lanes <- TRef.make(Lanes.empty[K, Entry[I, E, A]](limits)).commit
      open  <- TRef.make(true).commit
      dike = Live(key, weigh, call, extract, lanes, open)
      // Finalizers run in reverse order: workers are interrupted first, then whatever is still waiting is released.
      _ <- ZIO.addFinalizer(dike.close)
      _ <- ZIO.foreachDiscard(1 to config.maxInFlightBatches)(_ => dike.worker.forkScoped)
    yield dike

  /**
   * All state lives in one `TRef`, and every change to it is one STM transaction: admission is atomic with `close`, and
   * taking a batch is atomic with marking its key busy, so neither can interleave with anything else.
   */
  final private class Live[R, E, K, I, B, A](
    key: I => K,
    weigh: I => Long,
    call: (K, NonEmptyChunk[I]) => ZIO[R, E, B],
    extract: (I, B) => A,
    lanes: TRef[Lanes[K, Entry[I, E, A]]],
    open: TRef[Boolean]
  ) extends BulkDike[I, E, A]:

    override def apply(input: I): IO[BulkDikeError[E], A] =
      ZIO.uninterruptibleMask { restore =>
        for
          result      <- Promise.make[BulkDikeError[E], A]
          submittedAt <- Clock.nanoTime
          item = Lanes.Item(Entry(input, result), weigh(input) max 0L, submittedAt)
          _ <- admit(key(input), item)
          a <- restore(result.await).onInterrupt(result.interrupt)
        yield a
      }

    override def metrics: UIO[BulkDike.Metrics] =
      lanes.get.map(l => BulkDike.Metrics(l.queued, l.busy)).commit

    private def admit(k: K, item: Lanes.Item[Entry[I, E, A]]): IO[BulkDikeError[E], Unit] =
      open.get
        .flatMap { isOpen =>
          if isOpen then lanes.modify(l => l.admit(k, item).fold(Admission.Full -> l)(Admission.Admitted -> _))
          else STM.succeed(Admission.Closed)
        }
        .commit
        .flatMap {
          case Admission.Admitted => ZIO.unit
          case Admission.Full     => ZIO.fail(BulkDikeError.Rejected)
          case Admission.Closed   => ZIO.interrupt
        }

    def close: UIO[Unit] =
      (open.set(false) *> lanes.modify(_.drain)).commit.flatMap(ZIO.foreachDiscard(_)(_.result.interrupt))

    /** Takes and runs one batch at a time, forever. One worker runs per batch slot. */
    def worker: URIO[R, Nothing] =
      Ref
        .make(Chunk.empty[Entry[I, E, A]])
        .flatMap { taken =>
          take(taken)
            .flatMap((k, batch) => run(k, batch).ensuring(lanes.update(_.release(k)).commit))
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

    private def run(k: K, batch: NonEmptyChunk[Entry[I, E, A]]): URIO[R, Unit] =
      // Callers interrupted while their input was waiting have already completed their promise.
      ZIO.filterNot(batch)(_.result.isDone).map(NonEmptyChunk.fromChunk).flatMap {
        case None          => ZIO.unit
        case Some(entries) =>
          call(k, entries.map(_.input)).exit.flatMap {
            case Exit.Success(b) =>
              ZIO.foreachDiscard(entries)(entry => entry.result.complete(ZIO.succeed(extract(entry.input, b))))
            case Exit.Failure(cause) =>
              ZIO.foreachDiscard(entries)(_.result.failCause(cause.map(BulkDikeError.Failed(_))))
          }
      }
