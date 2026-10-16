# 0009. Drop mutation testing

Status: Accepted. Supersedes the mutation-testing layer of [0002](0002-layered-verification.md).

## Context

[0002](0002-layered-verification.md) added mutation testing as evidence that the suite fails on wrong code: Stryker4s
for syntactic mutants, and a script of hand-written concurrency mutants. Across both constructors it found no bug in the
code. It did find gaps in the tests, which were closed; the tests that close them stay.

Its ongoing cost is real:

- the hand-written mutants patch source text, so they must be kept in sync with every change to the code;
- Stryker aborts the whole run on a mutant that does not compile, and its runs were not always reproducible;
- every run costs one full test run per mutant.

For the failure modes that matter here, races, the evidence it was meant to give comes more directly from elsewhere:
each formal model ships with weakened variants that must fail, and the randomised suites check every trace against the
contract. The mutants that only the formal models catch were already the most important ones.

## Decision

Remove Stryker4s, the hand-written mutants and the weekly mutation workflow. Keep the other layers of 0002 and its rule
that every formal model ships with weakened variants that must fail.

## Consequences

- Nothing checks mechanically that a new test would fail on wrong code; a reviewer has to ask. For a race, the answer
  is a formal variant that must fail.
- `FERRY_SAMPLES` and `FERRY_TEST_TIMEOUT_SECONDS` stay: searching longer before a release, and failing a hung
  test fast, are useful without mutants.
- **Revisit when** a bug reaches a release that the suite should have caught, which would mean the tests are weaker than
  they look.
