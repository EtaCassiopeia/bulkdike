# 0005. Pair per-key batching with a fenced, idempotent write

Status: Accepted (a usage pattern: the library provides the batching, the application provides the write)

## Context

Per-key batching ([0003](0003-per-key-batching.md)) makes a process the only *local* writer of each key. Across a fleet
of processes, that holds only if each key's traffic reaches one process, which is a routing concern: consistent or
rendezvous hashing of the key over the fleet's members. Routing is never perfect: during a membership change two
processes may both believe they own a key, a crashed owner may have writes still on the wire, a response may be lost
after its write was applied, and clients retry.

The batcher holds inputs only in memory, so a crash loses what was waiting. That is safe only if nothing was
acknowledged before it was durable, and if the client's retry cannot apply an update twice.

## Decision

Correctness must not depend on routing. Routing to a single owner is an optimisation, which keeps batches full and
contention away; safety comes from the write. The batch call:

1. **reads** the entity's current state, including which updates it already contains;
2. **leaves out updates already applied** (deduplication by a client-chosen identifier), so a retry after a lost response
   answers from what was stored instead of applying again;
3. **writes the rest conditionally** on the state not having changed since the read (the fence: a version, a sequence
   number or a log length), so a stale or concurrent writer is refused rather than interleaved;
4. **retries the whole round trip** on a refused or failed write, with exponential backoff and jitter; the retry re-reads,
   so it sees whatever landed;
5. **answers each caller only after the write is durable**, with the stored result.

Both safeguards are needed, and each is shown to be needed:

- `formal/FencedWriter.tla` models any number of nodes writing one key with arbitrary routing, crashes with writes in
  flight, and lost answers. It checks that no update is applied twice and every answer is true. Without the fence, two
  writers interleave; without deduplication, a retry after a lost answer applies an update twice.
- `FencedWriterSimulation` runs the real `perKey` implementation through the same faults on virtual time, plus
  throttling and misrouted clients, and checks the same properties, with a deterministic scenario for each safeguard.
- `examples/.../OrderedWrites.scala` shows the write in code.

## Consequences

- A crash turns into a retry wave of at most the inputs waiting and in flight on that process, bounded by
  `maxQueueing`. Clients must retry on failure, interruption and `Rejected`, and their timeouts must exceed the
  expected batch latency, or they retry while their batch is still committing (safe, but wasted).
- Retries must carry the same identifier and the same content; a store that checks both turns a mismatched retry into a
  clear error instead of a silent duplicate.
- Wall-clock time from the batcher (submission instants, deadlines) is for scheduling only. Nothing it stores should be
  derived from when a batch happened to be dispatched.
- No write-ahead log is kept in the batcher: it would make the process a second source of truth. Durability is the
  store's; redelivery is the client's.
- Membership and routing are out of scope for this library.
