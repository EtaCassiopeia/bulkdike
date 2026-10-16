package ferry

import zio.Duration

/**
 * Validated settings for a [[Ferry.perKey]]. Construct with [[PerKeyConfig.make]].
 *
 * @param maxBatchSize
 *   A key's batch is dispatched as soon as it holds this many inputs.
 * @param maxBatchWeight
 *   A key's batch is dispatched as soon as its inputs weigh this much in total, and never carries more, except for a
 *   single input that is heavier on its own: it travels alone. Use it to stay under a per-request byte limit
 *   downstream.
 * @param maxBatchDelay
 *   How long a partial batch may wait for more inputs after its oldest input was submitted. The default, zero, sends an
 *   idle key's input at once, directly from its caller, and whatever is waiting as soon as the key's previous call
 *   completes: under load batches then grow by themselves, at no added latency. A positive delay batches every input,
 *   even an idle key's.
 * @param maxInFlightBatches
 *   Maximum number of batch calls running concurrently, across all keys. Each key has at most one. Direct calls for
 *   idle keys are not counted.
 * @param maxQueueing
 *   Maximum number of inputs waiting, across all keys. Further inputs are rejected with [[FerryError.Rejected]].
 * @param maxQueuedWeight
 *   Maximum total weight of the inputs waiting, across all keys. Further inputs are rejected with
 *   [[FerryError.Rejected]]. With weights in bytes, this bounds the memory held by waiting inputs.
 * @param directIdleKeys
 *   Whether, with a zero `maxBatchDelay`, an idle key's input is sent at once by its own caller (the default), or by a
 *   worker like any batch. Turn it off when the downstream needs a hard bound on concurrent calls: direct calls are not
 *   counted against `maxInFlightBatches`.
 */
final case class PerKeyConfig private (
  maxBatchSize: Int,
  maxBatchWeight: Long,
  maxBatchDelay: Duration,
  maxInFlightBatches: Int,
  maxQueueing: Int,
  maxQueuedWeight: Long,
  directIdleKeys: Boolean
)

object PerKeyConfig:

  def make(
    maxBatchSize: Int,
    maxInFlightBatches: Int,
    maxQueueing: Int,
    maxBatchDelay: Duration = Duration.Zero,
    maxBatchWeight: Long = Long.MaxValue,
    maxQueuedWeight: Long = Long.MaxValue,
    directIdleKeys: Boolean = true
  ): Either[InvalidConfig, PerKeyConfig] =
    InvalidConfig.unless(
      InvalidConfig.batching(maxBatchSize, maxBatchDelay, maxInFlightBatches, maxQueueing) ++ Seq(
        Option.when(maxBatchWeight < 1)(s"maxBatchWeight must be at least 1, got $maxBatchWeight"),
        Option.when(maxQueuedWeight < maxBatchWeight)(
          s"maxQueuedWeight ($maxQueuedWeight) must be at least maxBatchWeight ($maxBatchWeight), or batches could never fill"
        )
      )*
    )(
      new PerKeyConfig(
        maxBatchSize,
        maxBatchWeight,
        maxBatchDelay,
        maxInFlightBatches,
        maxQueueing,
        maxQueuedWeight,
        directIdleKeys
      )
    )
