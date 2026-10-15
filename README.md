# BulkDike

A ZIO 2 micro-batching bulkhead. Concurrent callers each ask for one thing. BulkDike groups their inputs into
batched calls to an external system. It caps how many batch calls run at once and how many inputs may wait, and gives
every caller its own share of the batch result.

It started as the queueing core of
[api-aggregation-service](https://github.com/EtaCassiopeia/api-aggregation-service), inspired by
[rezilience](https://github.com/svroonland/rezilience)'s `Bulkhead`. This is a rewrite for Scala 3 and ZIO 2.1.

```scala
import bulkdike.*
import zio.*

val config = BulkDikeConfig.make(
  maxBatchSize       = 5,           // dispatch as soon as 5 inputs are waiting...
  maxBatchDelay      = 5.seconds,   // ...or 5s after the oldest of them arrived
  maxInFlightBatches = 2,           // at most 2 batch calls at a time
  maxQueueing        = 50           // beyond 50 waiting inputs, reject immediately
)

// One upstream call answers many keys; each caller gets the value for its own key.
val prices: URIO[Scope, BulkDike[String, PricingError, Option[Price]]] =
  ZIO.fromEither(config).orDie.flatMap(BulkDike.keyed(_)(countries => pricingApi.fetch(countries)))

prices.flatMap(dike => dike("NL"))   // IO[BulkDikeError[PricingError], Option[Price]]
```

For batch results that are not a map, `BulkDike.make(config)(call)(extract)` takes any
`NonEmptyChunk[I] => ZIO[R, E, B]` together with an `(I, B) => A` that picks out each caller's part.

## Behaviour

| Situation | What the caller sees |
|---|---|
| Its batch call succeeds | its own result (`extract(input, batchResult)`) |
| Its batch call fails with `e` | `BulkDikeError.Failed(e)`, the same for everyone in that batch |
| Its batch call dies, or `extract` throws | the defect (a throwing `extract` affects only that caller) |
| `maxQueueing` inputs are already waiting | `BulkDikeError.Rejected`, straight away; nothing is sent |
| The caller is interrupted before dispatch | its input is left out of the batch |
| The scope closes while it waits | it is interrupted, whether queued, being assembled or in flight |
| It calls after the scope closed | it is interrupted |

After a failed or defective batch the BulkDike keeps serving later calls. `metrics` reports queued inputs and running
batches.

Retries, timeouts and circuit breaking belong around the batch call, where one policy decision covers the whole
batch. See [`PricingAggregation`](examples/src/main/scala/bulkdike/examples/PricingAggregation.scala), which wraps it
with rezilience's `Timeout`, `Retry` and `CircuitBreaker`.

### What changed from the original

| Original (ZIO 1, Scala 2.13) | Now |
|---|---|
| Rejection was checked after the stream dequeued an input, and `offer` on a bounded queue suspends, so callers piled up instead of being rejected | Admission decides immediately, under a lock it shares with shutdown |
| `maxInFlightCalls` limited how many promises were completed at once; batch calls ran one after another | `maxInFlightBatches` limits concurrent batch calls (one worker per slot) |
| `groupedWithin` restarts its timer after every emission, so a partial batch could leave long before the oldest input had waited `maxBatchDelay` | The deadline is the oldest input's arrival time plus `maxBatchDelay` |
| A batch call that died killed the stream, and every waiting caller hung | Failures and defects reach the batch's callers; later calls are still served |
| An interrupted caller's input was still sent | It is dropped before dispatch |
| Closing the scope left waiting callers hanging | Every waiting caller is interrupted |
| cats `Semigroup` combined the inputs | The batch call receives a `NonEmptyChunk` of them |
| Settings were unchecked defaults | `BulkDikeConfig.make` validates and reports every problem at once |

## How it is verified

Each layer catches a kind of bug the others miss.

| Layer | Where | What it establishes |
|---|---|---|
| Unit tests | `BulkDikeSpec` | Each documented behaviour, on the deterministic `TestClock` |
| Reference model | `BulkDikeModelSpec` | Over random sequences of arrivals and time steps, the real BulkDike dispatches exactly the batches an executable specification predicts, with the same contents, order and instants |
| Deterministic simulation | `Simulation`, `BulkDikePropertySpec` | Random workloads (bursts, latencies, failures, defects, interrupted callers, mid-flight shutdown) run on virtual time. Every trace is checked: no input sent twice, none sent after rejection or after its caller gave up, batch size and concurrency within limits, no partial batch before its deadline, every caller answered correctly, none stranded |
| Stress | `BulkDikeStressSpec` | The same accounting on the real multi-threaded scheduler, with thousands of callers and closes racing arrivals, repeated many times |
| Formal model | `formal/BulkDike.tla` | TLC explores every interleaving of a small instance (about 416k states). No input is sent twice, and every admitted caller is eventually answered (liveness). Two weakened variants must fail, which shows that both the admission lock and the cleanup on worker interruption are necessary |
| Mutation testing | Stryker4s, `mutation/semantic-mutants.py` | Measures whether the tests notice injected bugs: syntactic ones via Stryker, and hand-written concurrency bugs (missing interruption handling, missing locks, off-by-one deadlines) via the semantic harness |

```sh
sbt test                                   # everything except the two below
formal/check.sh path/to/tla2tools.jar      # model checking (~1 min)
sbt core/stryker                           # syntactic mutation testing
python3 mutation/semantic-mutants.py       # semantic mutation testing
```

`BULKDIKE_SAMPLES=n` changes how many cases the randomised suites try. Raise it before a release to search longer,
or lower it to keep mutation runs affordable.

## Build

Scala 3.3 LTS (the published baseline) and 3.9, ZIO 2.1, sbt 2. JDK 17 or newer.

```sh
sbt check          # formatting
sbt test
sbt examples/run
```

## License

Apache 2.0
