# Getting started

## Requirements

- Scala 3.3 LTS or later. The library is compiled against 3.3, so any Scala 3 project can use it.
- ZIO 2.1.
- JDK 17 or later.

## Add the dependency

BulkDike is not yet published to Maven Central. Until it is, publish it to your local repository:

```sh
git clone https://github.com/EtaCassiopeia/bulkdike.git
cd bulkdike
sbt core/publishLocal
```

Then depend on it:

```scala
libraryDependencies += "io.github.etacassiopeia" %% "bulkdike" % "0.1.0-SNAPSHOT"
```

Its only dependency is ZIO itself.

## A first dike

A dike lives in a `Scope`. Closing the scope stops its workers and interrupts any caller still waiting, so create it
once, at startup, next to the client it protects.

The common case is an API that answers a batch of inputs with one result per distinct input. `BulkDike.keyed` covers
it:

```scala
import bulkdike.*
import zio.*

--8<-- "Snippets.scala:first-dike"
```

`prices("NL")` waits until a batch carrying `"NL"` returns, then answers with that country's price, or `None` if the
batch result has no entry for it. Each distinct input is sent once per batch, even if several callers ask for it.

`BulkDikeConfig.make` and `PerKeyConfig.make` return an `Either` that lists every invalid setting at once, so a
misconfiguration fails at startup, not on the first call.

## Run the examples

Two runnable programs live in [`examples/`](https://github.com/EtaCassiopeia/bulkdike/tree/main/examples/src/main/scala/bulkdike/examples):

```sh
sbt "examples/runMain bulkdike.examples.PricingAggregation"
sbt "examples/runMain bulkdike.examples.OrderedWrites"
```

- **PricingAggregation** coalesces concurrent price lookups and wraps each batch call in a timeout, a retry and a
  circuit breaker.
- **OrderedWrites** appends entries to per-entity logs through `perKey`, ordered by effective day, with a fenced,
  idempotent write that survives throttling and conflicts.

## Next

- [Batching calls](guide/batching.md): `make` and `keyed` in depth.
- [Per-key batching](guide/per-key.md): ordered writes per entity.
- [Configuration and tuning](guide/configuration.md): every setting, and how to choose it.
