package bulkdike

import zio.{Chunk, Duration, NonEmptyChunk}

/**
 * Validated settings for a [[BulkDike]]. Construct with [[BulkDikeConfig.make]].
 *
 * @param maxBatchSize
 *   A batch is dispatched as soon as it holds this many inputs.
 * @param maxBatchDelay
 *   A partial batch is dispatched at the latest this long after its oldest input was submitted (or as soon as a batch
 *   slot frees up, if all slots were busy at that moment). `Duration.Zero` dispatches whatever is waiting immediately.
 * @param maxInFlightBatches
 *   Maximum number of batch calls running concurrently.
 * @param maxQueueing
 *   Maximum number of inputs waiting for a batch. Further inputs are rejected with [[BulkDikeError.Rejected]].
 */
final case class BulkDikeConfig private (
  maxBatchSize: Int,
  maxBatchDelay: Duration,
  maxInFlightBatches: Int,
  maxQueueing: Int
)

object BulkDikeConfig:

  def make(
    maxBatchSize: Int,
    maxBatchDelay: Duration,
    maxInFlightBatches: Int,
    maxQueueing: Int
  ): Either[InvalidConfig, BulkDikeConfig] =
    val problems = Chunk(
      Option.when(maxBatchSize < 1)(s"maxBatchSize must be at least 1, got $maxBatchSize"),
      Option.when(maxBatchDelay.isNegative)(s"maxBatchDelay must not be negative, got $maxBatchDelay"),
      Option.when(maxInFlightBatches < 1)(s"maxInFlightBatches must be at least 1, got $maxInFlightBatches"),
      Option.when(maxQueueing < maxBatchSize)(
        s"maxQueueing ($maxQueueing) must be at least maxBatchSize ($maxBatchSize), or batches could never fill"
      )
    ).flatten

    NonEmptyChunk.fromChunk(problems) match
      case Some(errors) => Left(InvalidConfig(errors))
      case None         => Right(new BulkDikeConfig(maxBatchSize, maxBatchDelay, maxInFlightBatches, maxQueueing))

final case class InvalidConfig(problems: NonEmptyChunk[String])
    extends IllegalArgumentException(problems.mkString("Invalid BulkDikeConfig: ", "; ", ""))
