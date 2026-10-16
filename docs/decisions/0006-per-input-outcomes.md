# 0006. Report outcomes per input; fail a batch only when it did not happen

Status: Accepted

## Context

When a batch call fails, every caller in the batch fails with it. That is right when the batch as a whole did not
happen (the downstream was unavailable, the write was refused), and wrong when one input was invalid: one bad input
would fail every other input that happened to share its batch, and batching would turn one caller's mistake into many
callers' errors.

## Decision

No new API. The batch call's result type `B` and the extracted result type `A` are free, so:

- the batch call returns **one outcome per input** (for example `Map[Id, Either[Rejection, Result]]`), and applies the
  valid inputs even if some are invalid;
- the batch call **fails only when the batch as a whole did not happen**; that failure reaches every caller as
  `FerryError.Failed`, and is the one worth retrying;
- `extract` hands each caller its own outcome.

This mirrors databases that accept a batch and return a result code per item. The contract is documented on
`Ferry.perKey`, tested in `PerKeySpec`, and used by the example.

## Consequences

- Callers distinguish three cases: their input was rejected on its merits (an `A` carrying the rejection, not worth
  retrying), the batch failed (`Failed`, worth retrying), or the ferry was full (`Rejected`, worth retrying after a
  backoff).
- An exception thrown by `extract` still affects only its own caller, as a defect.
