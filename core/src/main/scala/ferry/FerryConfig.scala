package ferry

import zio.{Duration, NonEmptyChunk}

/**
 * Validated settings for a [[Ferry]]. Construct with [[FerryConfig.make]].
 *
 * @param maxBatchSize
 *   A batch is dispatched as soon as it holds this many inputs.
 * @param maxBatchDelay
 *   A partial batch is dispatched at the latest this long after its oldest input was submitted (or as soon as a batch
 *   slot frees up, if all slots were busy at that moment). `Duration.Zero` dispatches whatever is waiting immediately.
 * @param maxInFlightBatches
 *   Maximum number of batch calls running concurrently.
 * @param maxQueueing
 *   Maximum number of inputs waiting for a batch. Further inputs are rejected with [[FerryError.Rejected]].
 */
final case class FerryConfig private (
  maxBatchSize: Int,
  maxBatchDelay: Duration,
  maxInFlightBatches: Int,
  maxQueueing: Int
)

object FerryConfig:

  def make(
    maxBatchSize: Int,
    maxBatchDelay: Duration,
    maxInFlightBatches: Int,
    maxQueueing: Int
  ): Either[InvalidConfig, FerryConfig] =
    InvalidConfig.unless(
      InvalidConfig.batching(maxBatchSize, maxBatchDelay, maxInFlightBatches, maxQueueing)*
    )(new FerryConfig(maxBatchSize, maxBatchDelay, maxInFlightBatches, maxQueueing))

final case class InvalidConfig(problems: NonEmptyChunk[String])
    extends IllegalArgumentException(problems.mkString("Invalid Ferry configuration: ", "; ", ""))

object InvalidConfig:

  /** `valid`, unless any of `problems` is present. */
  private[ferry] def unless[A](problems: Option[String]*)(valid: => A): Either[InvalidConfig, A] =
    NonEmptyChunk.fromIterableOption(problems.flatten).fold(Right(valid))(errors => Left(InvalidConfig(errors)))

  /** The constraints shared by every kind of Ferry. */
  private[ferry] def batching(
    maxBatchSize: Int,
    maxBatchDelay: Duration,
    maxInFlightBatches: Int,
    maxQueueing: Int
  ): Seq[Option[String]] =
    Seq(
      Option.when(maxBatchSize < 1)(s"maxBatchSize must be at least 1, got $maxBatchSize"),
      Option.when(maxBatchDelay.isNegative)(s"maxBatchDelay must not be negative, got $maxBatchDelay"),
      Option.when(maxInFlightBatches < 1)(s"maxInFlightBatches must be at least 1, got $maxInFlightBatches"),
      Option.when(maxQueueing < maxBatchSize)(
        s"maxQueueing ($maxQueueing) must be at least maxBatchSize ($maxBatchSize), or batches could never fill"
      )
    )
