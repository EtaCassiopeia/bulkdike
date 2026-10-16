package bulkdike

/**
 * The order in which a [[BulkDike.perKey]] sends a key's waiting inputs: ascending `rank`, ties in arrival order.
 *
 * Inputs that wait together are sent in rank order, so each batch is in rank order and, under load, so is everything
 * that arrived during the previous round trip. An input that arrives after inputs of a higher rank were already sent is
 * not held back or rejected: it goes in the key's next batch, and the downstream, which holds the key's state, decides
 * where it belongs. To give out-of-order inputs more time to meet, set a positive `maxBatchDelay`.
 *
 * `rank` is evaluated once per input, when it is submitted. It must be pure, total and cheap.
 *
 * Implement it to rank by anything an input carries, e.g. an effective date as `date.toEpochDay`, a timestamp as epoch
 * milliseconds, or a sequence number.
 */
trait Order[-I]:
  def rank(input: I): Long

object Order:

  /** Arrival order: every input has the same rank. The default. */
  val arrival: Order[Any] = _ => 0L

  /** Ascending `rank`, ties in arrival order. */
  def by[I](rank: I => Long): Order[I] = rank(_)
