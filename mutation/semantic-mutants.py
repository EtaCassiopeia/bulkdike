#!/usr/bin/env python3
"""Semantic mutation testing for BulkDike.

Stryker4s mutates syntax (operators, literals, conditions). It cannot express the bugs that matter most in
concurrent code: a missing interruption handler, a lock that is not taken, a region that is not uninterruptible.
Each mutant below is such a bug, written by hand. For each one this script copies the repository, applies the
mutant, runs the test suite, and reports whether the suite noticed.

Usage: mutation/semantic-mutants.py [mutant-name ...]     (default: all)
Env:   BULKDIKE_SAMPLES  sample count for the randomised suites (default here: 25)
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = Path("core/src/main/scala/bulkdike/BulkDike.scala")

# name -> (why it matters, text to replace, replacement)
MUTANTS = {
    "no-worker-cleanup": (
        "an interrupted worker strands the callers whose inputs it had taken",
        "            .onInterrupt(taken.get.flatMap(ZIO.foreachDiscard(_)(_.result.interrupt)))\n",
        "",
    ),
    "no-drain-on-close": (
        "callers still queued at close wait forever",
        "accepting.set(false) *> queue.takeAll.flatMap(ZIO.foreachDiscard(_)(_.result.interrupt))",
        "accepting.set(false)",
    ),
    "keep-accepting-after-close": (
        "calls made after close are queued with nobody to serve them",
        "accepting.set(false) *> queue.takeAll",
        "queue.takeAll",
    ),
    "unserialised-admission": (
        "an admission checked before close can enqueue after the drain (the TLA+ counterexample)",
        """      accepting
        .modifyZIO { isAccepting =>
          if isAccepting then
            queue.offer(entry).map(offered => (if offered then Admission.Admitted else Admission.QueueFull) -> true)
          else ZIO.succeed(Admission.Closed -> false)
        }""",
        """      accepting.get
        .flatMap { isAccepting =>
          if isAccepting then
            queue.offer(entry).map(offered => if offered then Admission.Admitted else Admission.QueueFull)
          else ZIO.succeed(Admission.Closed)
        }""",
    ),
    "ignore-full-queue": (
        "a rejected input is reported as admitted, so its caller waits forever",
        "if offered then Admission.Admitted else Admission.QueueFull",
        "Admission.Admitted",
    ),
    "send-cancelled-inputs": (
        "inputs of callers that gave up are still sent",
        "ZIO.filterNot(batch)(_.result.isDone)",
        "ZIO.succeed(batch.toChunk)",
    ),
    "caller-interrupt-not-propagated": (
        "an interrupted caller's input is not withdrawn",
        "restore(result.await).onInterrupt(result.interrupt)",
        "restore(result.await)",
    ),
    "no-batch-delay": (
        "partial batches leave immediately instead of waiting to fill",
        "first.submittedAt + config.maxBatchDelay.toNanos",
        "first.submittedAt",
    ),
    "deadline-from-assembly": (
        "the deadline is measured from when assembly starts, not from the oldest input",
        ".flatMap(first => fill(NonEmptyChunk.single(first), first.submittedAt + config.maxBatchDelay.toNanos, taken))",
        ".flatMap(first => Clock.nanoTime.flatMap(now => fill(NonEmptyChunk.single(first), now + config.maxBatchDelay.toNanos, taken)))",
    ),
    "strict-deadline": (
        "a batch due exactly now is held back (off by one at the deadline)",
        "if now >= deadline then ZIO.succeed(grown)",
        "if now > deadline then ZIO.succeed(grown)",
    ),
    "no-forming-lock": (
        "two idle workers split what should be one batch",
        "(forming.withPermit(assemble(taken)) flatMap run)",
        "(assemble(taken) flatMap run)",
    ),
    "one-worker": (
        "maxInFlightBatches is ignored: batches run one at a time",
        "ZIO.foreachDiscard(1 to config.maxInFlightBatches)",
        "ZIO.foreachDiscard(1 to 1)",
    ),
    "extra-worker": (
        "one batch more than allowed runs concurrently",
        "ZIO.foreachDiscard(1 to config.maxInFlightBatches)",
        "ZIO.foreachDiscard(0 to config.maxInFlightBatches)",
    ),
    "extract-outside-promise": (
        "an exception in extract kills the worker instead of failing one caller",
        "entry.result.complete(ZIO.succeed(extract(entry.input, b)))",
        "entry.result.succeed(extract(entry.input, b))",
    ),
    "keyed-without-dedup": (
        "keyed sends duplicates",
        "call(distinct(inputs))",
        "call(inputs)",
    ),
    "in-flight-not-tracked": (
        "metrics never report running batches",
        "ZIO.acquireReleaseWith(inFlight.update(_ + 1))(_ => inFlight.update(_ - 1))",
        "ZIO.acquireReleaseWith(ZIO.unit)(_ => ZIO.unit)",
    ),
    "take-up-to-interruptible": (
        "entries taken by takeUpTo can be lost if the worker is interrupted before recording them",
        """        .tap(more => taken.update(_ ++ more))
        .uninterruptible""",
        """        .tap(more => taken.update(_ ++ more))""",
    ),
}


# Races a few instructions wide, which no scheduling-based test hits reliably. Each is instead shown to be a real
# bug by a TLA+ configuration that model-checks the protocol without the mechanism and must find a stranded caller.
COVERED_FORMALLY = {
    "unserialised-admission": "formal/UnserialisedAdmission.cfg",
    "take-up-to-interruptible": "formal/NonAtomicTake.cfg",
}


def run_tests(workdir: Path, env: dict) -> tuple[str, float, str]:
    """Runs the suite; returns ("passed" | "failed" | "invalid", seconds, output tail)."""
    start = time.time()
    proc = subprocess.run(
        ["sbt", "-Dsbt.color=false", "-batch", "core/test"],
        cwd=workdir,
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        timeout=1800,
    )
    out = proc.stdout
    if proc.returncode == 0:
        status = "passed"
    elif "Compilation failed" in out or re.search(r"\[error\] .*\.scala:\d+", out):
        status = "invalid"  # the mutant does not compile: says nothing about the tests
    else:
        status = "failed"
    return status, time.time() - start, out[-4000:]


def main() -> int:
    names = sys.argv[1:] or list(MUTANTS)
    unknown = [n for n in names if n not in MUTANTS]
    if unknown:
        print(f"unknown mutants: {unknown}; known: {list(MUTANTS)}", file=sys.stderr)
        return 2

    env = dict(os.environ)
    env.setdefault("BULKDIKE_SAMPLES", "25")
    # A hanging mutant should fail fast. The unmutated suite needs well under this per test at 25 samples.
    env.setdefault("BULKDIKE_TEST_TIMEOUT_SECONDS", "60")
    # Mutants often leave a binding unused; under fatal warnings that would be a compile error, not a test result.
    env["SBT_TPOLECAT_DEV"] = "true"
    survivors = []

    with tempfile.TemporaryDirectory(prefix="bulkdike-mutants-") as tmp:
        work = Path(tmp) / "repo"
        shutil.copytree(ROOT, work, ignore=shutil.ignore_patterns("target", ".bsp", ".git", "states"))
        source = work / SOURCE
        pristine = source.read_text()

        status, secs, tail = run_tests(work, env)
        if status != "passed":
            print(f"baseline run failed after {secs:.0f}s; fix the suite before mutating:\n{tail}", file=sys.stderr)
            return 2
        print(f"[baseline] unmutated suite passes in {secs:.0f}s", flush=True)

        for name in names:
            why, old, new = MUTANTS[name]
            if pristine.count(old) != 1:
                print(f"[stale ] {name}: pattern not found exactly once; update the mutant", flush=True)
                survivors.append(name)
                continue
            source.write_text(pristine.replace(old, new))
            status, secs, tail = run_tests(work, env)
            formal = status == "passed" and name in COVERED_FORMALLY
            verdict = "formal" if formal else {"passed": "SURVIVED", "failed": "killed", "invalid": "INVALID"}[status]
            print(f"[{verdict:8}] {name:32} {secs:6.0f}s  {why}", flush=True)
            if formal:
                print(f"           survives the tests; caught by model checking {COVERED_FORMALLY[name]}", flush=True)
            if status == "invalid":
                print(tail[-1500:], flush=True)
            if status != "failed" and not formal:
                survivors.append(name)
        source.write_text(pristine)
        # The copy got its own build server; don't leave it running after its directory is gone.
        subprocess.run(["sbt", "-batch", "shutdown"], cwd=work, env=env, capture_output=True, timeout=120)

    print(f"\n{len(names) - len(survivors)}/{len(names)} semantic mutants caught by the tests or the formal model")
    if survivors:
        print("survivors: " + ", ".join(survivors))
    return 1 if survivors else 0


if __name__ == "__main__":
    sys.exit(main())
