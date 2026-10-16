# 0004. Dispatch a key's next batch when its previous one completes

Status: Accepted

## Context

A batch delay trades latency for batch size: every input waits up to `maxBatchDelay` so that more can join it. Tuned for
peak load, it adds latency at every other load; tuned for low load, batches stay small at peak, which is exactly when
size matters. A fixed delay is right at one load only.

With one batch in flight per key ([0003](0003-per-key-batching.md)), there is a better signal than a timer: the key's
previous batch. While it is in flight, new inputs for the key cannot be sent anyway.

## Decision

`PerKeyConfig.maxBatchDelay` defaults to zero. A key's waiting inputs are dispatched as soon as the key is free and a
worker is available, so the next batch carries everything that arrived during the previous round trip:

- at low load, an input goes out alone, immediately: no added latency;
- at high load, batches grow by themselves to the arrival rate times the round-trip time, up to `maxBatchSize` and
  `maxBatchWeight`;
- as long as one batch can carry what arrives during one round trip, an input waits at most for the round trip in
  flight plus its own. Beyond that rate the key is saturated: inputs queue, and `maxQueueing` decides when to reject.

This is the group-commit pattern, and the way end-to-end batching databases (TigerBeetle, for one) keep latency low
without a batching window.

A positive `maxBatchDelay` is still available, for a downstream that charges per call regardless of load and rewards
fuller batches even when the key is idle.

## Consequences

- Batch size is no longer a configured constant but a function of load; dashboards should show the batch fill ratio
  (batch size over `maxBatchSize`) to read the load from it.
- With zero delay, `maxBatchSize` and `maxBatchWeight` are the only limits on a batch; they must reflect the
  downstream's real per-request limits.
