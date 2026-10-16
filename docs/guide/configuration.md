# Configuration and tuning

Both configurations are built by a `make` that returns `Either[InvalidConfig, _]`, listing every invalid setting at
once.

## `BulkDikeConfig` (for `make` and `keyed`)

| Setting | Meaning | Constraint |
|---|---|---|
| `maxBatchSize` | Send a batch as soon as it holds this many inputs. | ≥ 1 |
| `maxBatchDelay` | Send a partial batch at the latest this long after its oldest input arrived, or as soon as a slot frees if all were busy then. Zero sends whatever is waiting at once. | ≥ 0 |
| `maxInFlightBatches` | Batch calls running at once. | ≥ 1 |
| `maxQueueing` | Inputs that may wait. Beyond it, calls are rejected at once. | ≥ `maxBatchSize` |

## `PerKeyConfig` (for `perKey`)

| Setting | Default | Meaning | Constraint |
|---|---|---|---|
| `maxBatchSize` | required | Send a key's batch as soon as it holds this many inputs. | ≥ 1 |
| `maxInFlightBatches` | required | Batch calls running at once, across all keys; each key has at most one. Direct calls for idle keys are not counted. | ≥ 1 |
| `maxQueueing` | required | Inputs that may wait, across all keys. | ≥ `maxBatchSize` |
| `maxBatchDelay` | `0` | How long a partial batch may wait for more after its oldest input arrived. Zero sends a key's inputs as soon as its previous call completes, and an idle key's at once. | ≥ 0 |
| `maxBatchWeight` | unbounded | A batch's total weight. A single heavier input travels alone. | ≥ 1 |
| `maxQueuedWeight` | unbounded | Total weight that may wait, across all keys. | ≥ `maxBatchWeight` |
| `directIdleKeys` | `true` | With zero delay, send an idle key's input from its caller instead of through a worker. | — |

`perKey` also takes, next to the config:

| Parameter | Default | Meaning |
|---|---|---|
| `key: I => K` | required | The entity an input belongs to. |
| `weigh: I => Long` | `1` | An input's weight, e.g. its size in bytes. Negative weights count as 0. |
| `order: Order[I]` | `Order.arrival` | The order in which a key's waiting inputs are sent. See [Dispatch order](ordering.md). |

## Tuning `perKey`

Start with the defaults: zero delay, direct calls on. Then:

1. Set **`maxBatchSize`** and **`maxBatchWeight`** to the store's real per-request limits. With zero delay they are the
   only limits on a batch.
2. Set **`maxInFlightBatches`** to the number of busy keys the store can serve at once. Quiet keys bypass it.
3. Set **`maxQueueing`** and **`maxQueuedWeight`** from memory and from the latency you accept: a busy key that is
   saturated holds its inputs back, and these bound how much piles up behind it.
4. Turn **`directIdleKeys`** off only if the store needs one hard bound on concurrent calls.
5. Raise **`maxBatchDelay`** above zero only if the store charges per call regardless of load, or if out-of-order
   inputs need a wider window to be sorted together. Every input then pays that delay.

## Monitoring

`dike.metrics` reports the current load:

- `queued`: inputs waiting;
- `inFlightBatches`: batch calls running, including `perKey`'s direct calls.

```scala
--8<-- "Snippets.scala:metrics"
```

With dispatch on completion, batch size depends on load. The **fill ratio**, batch size over `maxBatchSize`,
measured in your batch call, shows how busy a key is. A ratio near one means the key is saturated.

## Scaling beyond one dike

All of a `perKey` dike's state is one value updated by STM transactions, which every caller contends on. If that ever
limits throughput, run several dikes, each owning a share of the keys. Every per-key guarantee is local to a key, so
nothing is lost:

```scala
--8<-- "Snippets.scala:shards"
```

Measure before you shard; see [0008](../decisions/0008-deferred.md).
