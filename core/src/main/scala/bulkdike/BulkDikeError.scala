package bulkdike

/** Why a call made through a [[BulkDike]] did not produce a result. */
enum BulkDikeError[+E]:

  /** The input was not accepted because `maxQueueing` inputs were already waiting. The batch call was not made. */
  case Rejected extends BulkDikeError[Nothing]

  /** The batch call that carried this input failed with `error`. */
  case Failed(error: E)

  final def fold[O](rejected: => O, failed: E => O): O = this match
    case Rejected      => rejected
    case Failed(error) => failed(error)

  final def toException: Exception = BulkDikeException(this)

final case class BulkDikeException[E](error: BulkDikeError[E])
    extends Exception(error.fold("BulkDike rejected the call: queue is full", e => s"BulkDike batch call failed: $e"))
