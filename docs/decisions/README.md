# Design decisions

Each record states a decision, why it was made, and what it costs. Records are not edited after the fact except to
mark them superseded; a changed decision gets a new record.

| #                                       | Decision                                                                 | Status   |
|-----------------------------------------|--------------------------------------------------------------------------|----------|
| [0001](0001-single-queue-batcher.md)    | One admission queue, one batch assembled at a time                       | Accepted |
| [0002](0002-layered-verification.md)    | Verify with tests, model checking and mutation testing together          | Accepted |
| [0003](0003-per-key-batching.md)        | Per-key batching with one batch in flight per key                        | Accepted |
| [0004](0004-dispatch-on-completion.md)  | Dispatch a key's next batch when its previous one completes              | Accepted |
| [0005](0005-fenced-single-writer.md)    | Pair per-key batching with a fenced, idempotent write                    | Accepted |
| [0006](0006-per-input-outcomes.md)      | Report outcomes per input; fail a batch only when it did not happen      | Accepted |
| [0007](0007-bound-by-weight.md)         | Bound batches and the waiting room by weight as well as count            | Accepted |
| [0008](0008-deferred.md)                | Deferred: sharding, lock-free admission, adaptive concurrency, and more | Deferred |
