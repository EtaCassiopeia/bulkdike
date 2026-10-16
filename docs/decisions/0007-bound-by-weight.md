# 0007. Bound batches and the waiting room by weight as well as count

Status: Accepted

## Context

`maxBatchSize` and `maxQueueing` count inputs. When inputs vary in size, counts bound neither of the two things that
actually run out:

- a downstream's **per-request limit** is usually in bytes, so a batch of `maxBatchSize` large inputs can be refused
  however few they are;
- **memory** held by waiting inputs is their total size, so `maxQueueing` large inputs can exhaust it.

## Decision

`BulkDike.perKey` takes an optional `weigh: I => Long` (defaulting to 1 per input), and `PerKeyConfig` two bounds:

- **`maxBatchWeight`**: a batch is dispatched once its inputs reach this weight and never carries more. A single input
  heavier than the limit travels alone rather than never: a batch always has at least one input, and the downstream
  decides what to do with an oversized one.
- **`maxQueuedWeight`**: an input that would take the waiting room's total weight above this bound is rejected, like
  one that would exceed `maxQueueing`.

Both default to unbounded, so weights cost nothing unless used. Validation requires
`maxQueuedWeight >= maxBatchWeight`, or a full batch could never form.

## Consequences

- With weights in bytes, memory held by waiting inputs is bounded by `maxQueuedWeight` plus a small per-input overhead.
  To keep that overhead small, inputs should be references to large payloads (an identifier or a storage locator),
  not the payloads themselves.
- An input's weight is computed once, at admission, and must not change while it waits.
- `BulkDike.make` does not take weights. Its users have not needed them, and adding them there would change its
  configuration for everyone; it can follow if a use case appears.
