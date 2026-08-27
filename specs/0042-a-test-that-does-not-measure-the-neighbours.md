# 0042 — A test that does not measure the neighbours

## Problem

`examples/RegressionTest` runs a scenario three times against a target whose
latency it controls, and asserts that the two runs where nothing changed compare
as `Indistinguishable`. It fails at random, and it fails for the reason this
repository has two open specs about.

Spec 0041 stopped `./gradlew build` from scheduling it, which was the urgent
half. The other half survives: run alone on a saturated machine it still failed
three times in six, its 20 ms target reading 41.68 ms then 54.26 ms on one pair
and 45.09 ms then 32.11 ms on another. Nothing about the target changed between
those numbers. The machine did.

The test compares two absolute measurements taken minutes apart and calls a
difference between them a property of the code. That is precisely the mistake
`[CINOISE]` describes, that 0038 exists to refuse and that 0039 exists to
measure — made by this repository's own test suite, against this repository's
own comparison. A tool whose headline claim is that it will not print a number
nobody measured should not have a test that does.

## Not doing

- No deleting the test. The loop it exercises — measure, keep, change, measure,
  ask — is the one users actually run, and it is the only end-to-end proof that
  the baseline format and the comparison agree.
- No retry, no `@RepeatedTest`, no tolerance widened until it passes. A wider
  tolerance is a weaker claim, not a steadier one.
- No new comparison semantics. 0038 owns the interval and the third verdict.
- No change to `CalibrationTest`, which has not failed once.

## Shape

The test asks its question against what the machine can resolve rather than
against a fixed number:

```kotlin
val floor = kestrel.calibrate()

withClue("the same target twice, on a machine that resolves ${floor.resolution}") {
    unchanged.shouldBeInstanceOf<Change.Indistinguishable>()
}
```

- The unchanged pair is judged against the measured floor from 0039, so a run on
  a loaded machine widens the band it has to sit inside instead of failing.
- The *changed* pair keeps a strict claim: the injected slowdown is deliberately
  far larger than any plausible floor, and a test that cannot see it is a test
  worth failing.
- Where the floor is so large that even the injected change is unresolvable, the
  test says the machine could not answer rather than passing quietly.

## Why this shape

Three options. Widening the tolerance to a fixed larger number is one line and
picks a constant on the author's machine that is wrong on everybody else's.
Comparing ratios rather than absolute durations removes the drift between the
two runs but still assumes the drift is proportional, which under a noisy
neighbour it is not. Consulting the measured floor is the most code and is the
only one that scales with the machine it is actually on — and it is code this
repository is already committing to for 0038 and 0039.

Recommend the third, and note the payoff: the repository's own suite becomes the
first consumer of `calibrate()`, which is the cheapest possible test of whether
that API is worth having.

## Stack

- [x] **`spec-0042-resolved`** ([#34](https://github.com/matthewjones372/kestrel/pull/34)) — `RegressionTest` judges its unchanged pair
      against 0039's measured floor, and says so in its clue.
      Done when: the test passes on a machine under heavy artificial load, still
      fails when the comparison genuinely misses an injected slowdown, and names
      the machine when the floor is too large to answer at all.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :examples:timingTests
```

and, for the claim that matters, under load:

```bash
./gradlew :examples:timingTests   # while the machine is busy
```

## Open questions

1. **Does this depend on 0039 landing first?** Yes, entirely — it consumes
    `calibrate()`. Recommend saying so here rather than inventing a second way
    to measure the floor, which is the mistake 0039 already argues against.
2. **Should the floor be measured once per class or once per pair?** Recommend
    once per class, cached as 0039 specifies: a floor measured between two runs
    is measuring the same drift it is supposed to bound.
3. **Is `[CINOISE]`'s 2.66% the right expectation for a developer laptop?** No —
    it is a figure for GitHub-hosted runners, and a laptop running eight modules'
    tests is worse. Recommend the test asserting nothing about the floor's size,
    only consuming it, so the number stays a measurement rather than becoming a
    threshold nobody re-measured.
