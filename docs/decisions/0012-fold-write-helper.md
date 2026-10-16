# 0012. Package "read once, apply, conditional write" as `FoldWrite`

Status: Accepted

## Context

A group of a key's inputs can be processed in two ways: hand it to the store's own group operation, or read the key's
state once, apply the inputs to it in memory, and write once. The second is the fenced single-writer pattern of
[0005](0005-fenced-single-writer.md) with per-input outcomes ([0006](0006-per-input-outcomes.md)), and every
application that uses it writes the same round trip: read, skip inputs an earlier attempt already applied, apply the
rest in order, write only if the store still holds what was read, retry the whole round trip on a conflict or a lost
response, answer each input on its own.

## Decision

`FoldWrite(id, read, step, write, retry)` is that round trip, usable as the batch call and the extract of
`BulkDike.perKey` (`write.call`, `write.outcome`):

- `step(state, input)` is the application's pure logic: the next state and the input's outcome. It sees the state as
  read, so it answers an input already applied without applying it again; that makes a retry after a lost response safe.
- `write(key, before, after)` must succeed only if the store still holds `before`; it is skipped when nothing changed.
- Inputs of one batch with the same `id` are applied once and share the first one's outcome.
- `retry` is the application's `Schedule`.

Handing a group to the store's group operation needs no helper: the batch call passes the batch through.

## Consequences

- The library still holds no state across a key's batches: `FoldWrite` reads the state for every batch, so the process
  never becomes a source of truth.
- An all-or-nothing group operation that rejects a whole group for one invalid input is handled inside `call`, by
  validating first or splitting.
- `FoldWriteSpec` checks conflicts, lost responses, repeated inputs and exhausted retries; the `OrderedWrites` example
  uses it with `Order.by` on an effective day.
