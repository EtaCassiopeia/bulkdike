# BulkDike

A ZIO 2 micro-batching bulkhead. It coalesces concurrent calls into batched calls to an external system, caps how
many batches run at once and how many inputs may wait, and gives each caller its own share of the result.

It is a Scala 3 rewrite of the queueing core of
[api-aggregation-service](https://github.com/EtaCassiopeia/api-aggregation-service), inspired by
[rezilience](https://github.com/svroonland/rezilience).

```scala
val config = BulkDikeConfig.make(
  maxBatchSize       = 5,         // dispatch when 5 inputs are waiting
  maxBatchDelay      = 5.seconds, // or 5s after the oldest arrived
  maxInFlightBatches = 2,         // concurrent batch calls
  maxQueueing        = 50         // beyond this, reject immediately
)

for
  cfg   <- ZIO.fromEither(config)
  dike  <- BulkDike.keyed(cfg)((countries: NonEmptyChunk[String]) => pricingApi.fetch(countries))
  price <- dike("NL") // IO[BulkDikeError[E], Option[Price]]
yield price
```

`BulkDike.make(config)(call)(extract)` handles batch results that aren't maps.

For writes that must stay in order per entity, `BulkDike.perKey` batches each key separately, with at most one batch in
flight per key. By default a key's next batch leaves as soon as its previous one completes, so batches grow with load
instead of waiting on a timer:

```scala
for
  cfg    <- ZIO.fromEither(PerKeyConfig.make(maxBatchSize = 25, maxInFlightBatches = 8, maxQueueing = 1000))
  writer <- BulkDike.perKey(cfg, (e: Entry) => e.entity)((entity, entries) => store.append(entity, entries))(
              (entry, outcomes) => outcomes(entry.id)
            )
yield writer
```

See the [ordered-writes example](examples/src/main/scala/bulkdike/examples/OrderedWrites.scala) for the fenced,
idempotent write it is meant to pair with.

## Behaviour

- A failed batch call fails every caller in the batch with `BulkDikeError.Failed(e)`; later calls are still served.
- When the queue is full, callers get `BulkDikeError.Rejected` straight away.
- An interrupted caller's input is dropped from its batch.
- Closing the scope interrupts every waiting caller; none is left hanging.
- `perKey` can also bound batches and the waiting room by weight (e.g. bytes), not just by count.

Put retries, timeouts and circuit breakers around the batch call. See the
[example](examples/src/main/scala/bulkdike/examples/PricingAggregation.scala), which uses rezilience for this.

## Verification

- **Unit, model-based, simulation and stress tests**: random workloads run on virtual time and on the real scheduler.
  Every trace is checked against the contract.
- **Fault-injection simulation** of the per-key pattern: crashes, lost responses, late writes and misrouted clients.
- **TLA+** (`formal/`): TLC checks every interleaving of both protocols and of the fenced-writer pattern, with
  weakened variants that must fail.
- **Mutation testing**: Stryker4s, plus 29 hand-written concurrency bugs (`mutation/`). All are caught by the tests or
  the formal model.

Design decisions and their reasons are recorded in [`docs/decisions`](docs/decisions/README.md).

```sh
sbt test
formal/check.sh path/to/tla2tools.jar
sbt core/stryker
python3 mutation/semantic-mutants.py
```

Scala 3.3 LTS / 3.9, ZIO 2.1, sbt 2, JDK 17+. Apache 2.0.
