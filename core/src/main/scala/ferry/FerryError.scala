package ferry

/** Why a call made through a [[Ferry]] did not produce a result. */
enum FerryError[+E]:

  /** The input was not accepted because `maxQueueing` inputs were already waiting. The batch call was not made. */
  case Rejected extends FerryError[Nothing]

  /** The batch call that carried this input failed with `error`. */
  case Failed(error: E)

  final def fold[O](rejected: => O, failed: E => O): O = this match
    case Rejected      => rejected
    case Failed(error) => failed(error)

  final def toException: Exception = FerryException(this)

final case class FerryException[E](error: FerryError[E])
    extends Exception(error.fold("Ferry rejected the call: queue is full", e => s"Ferry batch call failed: $e"))
