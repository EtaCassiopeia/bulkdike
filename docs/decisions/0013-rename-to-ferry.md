# 0013. Rename BulkDike to Ferry

Status: Accepted

## Context

The library was named BulkDike, after *bulkhead* and *dike*: something that holds back a flood. The name had three
problems:

- "dike" is also a spelling of an English slur, which makes a public library harder to adopt and recommend;
- "bulk" suggests bulk imports and bulk APIs, while the library's distinctive behaviour is the opposite: quiet keys are
  not batched at all;
- it describes the first constructor, a batcher in front of an API, and not the per-key writer that is now the
  library's main use.

Nothing was published yet, so renaming cost no user anything.

## Decision

The library is **Ferry**: artifact `zio-ferry`, package `ferry`, repository `EtaCassiopeia/zio-ferry`. A ferry carries
whoever gathered while it was away, and leaves again as soon as it is back: one crossing at a time per route, loads that
grow with the crowd, and a lone passenger who crosses at once. That is dispatch on completion
([0004](0004-dispatch-on-completion.md)) and direct calls for idle keys ([0010](0010-direct-calls-for-idle-keys.md)).

Every identifier follows: `BulkDike` is `Ferry`, `BulkDikeConfig` is `FerryConfig`, `BulkDikeError` is `FerryError`,
the formal model `BulkDike.tla` is `Ferry.tla`, and the test settings are `FERRY_SAMPLES` and
`FERRY_TEST_TIMEOUT_SECONDS`.

## Consequences

- Earlier records were updated to the new identifiers, so that their references still resolve; their decisions are
  unchanged.
- GitHub redirects the old repository URL. The old documentation site URL does not redirect.
