# BulkDike

[![CI](https://github.com/EtaCassiopeia/bulkdike/actions/workflows/ci.yml/badge.svg)](https://github.com/EtaCassiopeia/bulkdike/actions/workflows/ci.yml)
[![Docs](https://img.shields.io/badge/docs-etacassiopeia.github.io%2Fbulkdike-blue)](https://etacassiopeia.github.io/bulkdike/)

A ZIO 2 micro-batching bulkhead. It coalesces concurrent calls into batched calls to an external system, caps how
many batches run at once and how many inputs may wait, and gives each caller its own share of the result.

**[Documentation](https://etacassiopeia.github.io/bulkdike/)**: [getting started](https://etacassiopeia.github.io/bulkdike/getting-started/),
[guide](https://etacassiopeia.github.io/bulkdike/guide/batching/),
[architecture](https://etacassiopeia.github.io/bulkdike/architecture/),
[verification](https://etacassiopeia.github.io/bulkdike/verification/),
[API](https://etacassiopeia.github.io/bulkdike/api/).

```scala
for
  cfg   <- ZIO.fromEither(BulkDikeConfig.make(maxBatchSize = 5, maxBatchDelay = 5.millis, maxInFlightBatches = 2, maxQueueing = 50))
  dike  <- BulkDike.keyed(cfg)((countries: NonEmptyChunk[String]) => pricingApi.fetch(countries))
  price <- dike("NL") // IO[BulkDikeError[E], Option[Price]]
yield price
```

For writes that must stay in order per entity, `BulkDike.perKey` batches each key separately, with at most one call in
flight per key. Idle keys are not batched at all; busy keys get batches that grow with load, sent in an
[order](https://etacassiopeia.github.io/bulkdike/guide/ordering/) of your choice. `FoldWrite` packages the fenced,
idempotent "read once, apply, conditional write" call to pair it with.

Every caller gets an answer, overload fails fast, and per-key order holds. This is checked by fault-injecting
simulations, stress tests and TLA+ models with weakened variants that must fail.

```sh
sbt testFull
formal/check.sh path/to/tla2tools.jar
```

A Scala 3 rewrite of the queueing core of
[api-aggregation-service](https://github.com/EtaCassiopeia/api-aggregation-service), inspired by
[rezilience](https://github.com/svroonland/rezilience). Scala 3.3 LTS / 3.9, ZIO 2.1, sbt 2, JDK 17+. Apache 2.0.
