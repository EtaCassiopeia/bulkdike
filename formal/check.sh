#!/usr/bin/env bash
# Model-checks the Ferry protocols with TLC. Each spec has one configuration that must pass, and weakened
# variants that must FAIL: each variant removes one mechanism, and its failure proves that mechanism is needed.
#
#   Ferry.tla      Ferry.make: one queue, a forming lock, interruptible workers (safety and liveness)
#     UnserialisedAdmission, NoCleanupOnInterrupt, NonAtomicTake      -> a caller is stranded
#   PerKey.tla        Ferry.perKey: per-key lanes in rank order, one call in flight per key, idle keys called directly
#                     (PerKey) or every input batched (PerKeyBatchOnly); safety and liveness
#     PerKeyUnsorted      -> inputs waiting together are sent out of rank order
#     PerKeyNoBusyGuard, PerKeyNoDirectGuard, PerKeyNoAwaitCall -> a key has two calls in flight
#     PerKeyNonAtomicTake, PerKeyNonAtomicAdmission -> a caller is stranded
#   FencedWriter.tla  the single-writer-per-key deployment pattern, against a store with conditional appends
#     FencedWriterUnfenced, FencedWriterNoDedup                         -> a command is applied twice
#
# Usage: formal/check.sh [path/to/tla2tools.jar]   (defaults to $TLA2TOOLS or ./tla2tools.jar)
set -euo pipefail

cd "$(dirname "$0")"
JAR="${1:-${TLA2TOOLS:-tla2tools.jar}}"
META="$(mktemp -d)"
trap 'rm -rf "$META" ./*_TTrace_*.tla ./*_TTrace_*.bin' EXIT

tlc() { # spec config
  java -XX:+UseParallelGC -cp "$JAR" tlc2.TLC -workers auto -deadlock -metadir "$META/$2" -config "$2.cfg" "$1.tla"
}

passes() { # spec config
  echo "== $2 (expected: no error)"
  tlc "$1" "$2" | tail -n 4
}

fails() { # spec config
  echo "== $2 (expected: Safety violated)"
  if out="$(tlc "$1" "$2")"; then
    echo "$out" | tail -n 4
    echo "FAIL: $2 was expected to violate an invariant but passed" >&2
    exit 1
  fi
  echo "$out" | grep -q "Invariant Safety is violated" || { echo "$out" | tail -n 20; exit 1; }
  echo "violated, as expected"
}

passes Ferry Ferry
for variant in UnserialisedAdmission NoCleanupOnInterrupt NonAtomicTake; do fails Ferry "$variant"; done

passes PerKey PerKey
passes PerKey PerKeyBatchOnly
for variant in PerKeyUnsorted PerKeyNoBusyGuard PerKeyNoDirectGuard PerKeyNoAwaitCall PerKeyNonAtomicTake PerKeyNonAtomicAdmission; do
  fails PerKey "$variant"
done

passes FencedWriter FencedWriter
for variant in FencedWriterUnfenced FencedWriterNoDedup; do fails FencedWriter "$variant"; done
