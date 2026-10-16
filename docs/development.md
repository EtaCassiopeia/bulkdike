# Development

## Layout

| Path | Contents |
|---|---|
| `core/` | the library |
| `examples/` | runnable examples, and `Snippets.scala`: the code shown on this site, compiled so it cannot go stale |
| `formal/` | TLA+ models, their configurations, and `check.sh` |
| `docs/` | this site: guides, architecture, decision records |
| `mkdocs.yml` | site configuration |

## Build and test

sbt 2, JDK 17 or later:

```sh
sbt check                  # formatting
sbt fmt                    # format everything
sbt testFull               # every test; `test` is incremental in sbt 2
sbt "++3.9.0 testFull"     # against the newest Scala as well as the 3.3 LTS
```

CI runs formatting, the tests on JDK 17 and 21 with both Scala versions, both examples, and every TLA+ configuration.

## Formal models

Download [`tla2tools.jar`](https://github.com/tlaplus/tlaplus/releases) (CI uses v1.7.4), then:

```sh
formal/check.sh path/to/tla2tools.jar
```

A change to the protocol in `Ferry.scala`, `PerKeyFerry.scala` or `Lanes.scala` needs a matching change to its
model. A new mechanism needs a variant without it that must fail.

## Documentation

The site is built with [Material for MkDocs](https://squidfunk.github.io/mkdocs-material/) and published to GitHub
Pages from `main`, together with the Scaladoc API reference.

```sh
python3 -m venv .venv && .venv/bin/pip install -r requirements-docs.txt
.venv/bin/mkdocs serve     # live preview at http://127.0.0.1:8000
sbt core/doc               # the API reference, built separately
```

Code samples are included from `examples/` with `--8<-- "File.scala:section"`, and the section is marked in the source
with `// --8<-- [start:section]` and `// --8<-- [end:section]`. Put new samples in `Snippets.scala` so that CI compiles
them.

The build runs in strict mode: a broken link or a missing snippet fails it.

The animation in the README and on the home page, `docs/assets/ferry.svg`, is generated: edit
`scripts/ferry-svg.py` and run `python3 scripts/ferry-svg.py`.

## Decision records

A design decision gets a record in `docs/decisions`: context, decision, consequences. Records are not edited after the
fact except to mark them superseded; a changed decision gets a new record. Add it to the index in
`docs/decisions/README.md` and to `nav` in `mkdocs.yml`.
