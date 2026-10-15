#!/usr/bin/env bash
# Model-checks the BulkDike protocol with TLC.
#   BulkDike.cfg                - the protocol as implemented: must pass (safety and liveness).
#   UnserialisedAdmission.cfg   - without the admission lock: must FAIL, proving the lock is needed.
#   NoCleanupOnInterrupt.cfg    - without worker cleanup: must FAIL, proving the cleanup is needed.
#   NonAtomicTake.cfg           - take and record as separate steps: must FAIL, proving the atomicity is needed.
# Usage: formal/check.sh [path/to/tla2tools.jar]   (defaults to $TLA2TOOLS or ./tla2tools.jar)
set -euo pipefail

cd "$(dirname "$0")"
JAR="${1:-${TLA2TOOLS:-tla2tools.jar}}"
META="$(mktemp -d)"
trap 'rm -rf "$META" ./*_TTrace_*.tla ./*_TTrace_*.bin' EXIT

tlc() {
  java -XX:+UseParallelGC -cp "$JAR" tlc2.TLC -workers auto -deadlock -metadir "$META/$1" -config "$1.cfg" BulkDike.tla
}

echo "== BulkDike (expected: no error)"
tlc BulkDike | tail -n 4

for variant in UnserialisedAdmission NoCleanupOnInterrupt NonAtomicTake; do
  echo "== $variant (expected: NoneStrandedAfterClose violated)"
  if out="$(tlc "$variant")"; then
    echo "$out" | tail -n 4
    echo "FAIL: $variant was expected to violate an invariant but passed" >&2
    exit 1
  fi
  echo "$out" | grep -q "Invariant Safety is violated" || { echo "$out" | tail -n 20; exit 1; }
  echo "violated, as expected"
done
