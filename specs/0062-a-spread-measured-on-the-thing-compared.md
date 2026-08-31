# 0062 — A spread measured on the thing being compared

## Problem

0048 replaced a relative floor with an absolute one and predicted that would
stop `RegressionTest` flaking. Measured on this branch, at load 5 on four cores,
it still fails: the unchanged pair reported `Worse`, 25.0 ms against 63.2 ms.
Ten runs earlier in the day gave 8 passes and 2 failures, both the same shape.

0048's own Problem section contains the reason. It rejects `hiccups.p99` because
that bounds "what the *injector's own JVM* stalled for", and says what a
comparison needs is "the spread of *repeated identical measurements of a
target* — which includes the target's scheduling, its own JIT, and everything
else the machine does to it". `Floor.absolute` is the spread of a **null step's**
repeats. No socket, no server, no loopback stack. It is measured on the injector
alone and therefore has exactly the defect 0048 rejected the other number for —
and being smaller, it refuses less and flakes more.

The deeper point is that no floor can fix this. **A single run cannot bound its
own noise.** One measurement of a target says nothing about how far a second
would land from it, and a number taken from somewhere else — a null step, a
hiccup thread — is a guess wearing a measurement's clothes.

## Not doing

- No new statistics. 0038 already built the thing that answers this.
- No removal of `Floor`. It answers a different and narrower question — whether
  this machine can resolve anything at all — and 0039 is right that a report
  should say so.
- No change to `Comparison`, which is the right shape for one run against one
  baseline: it says what moved, and it should stop implying it knows whether the
  move was real.
- No change to what `RegressionTest` is for.

## Shape

0038's `Difference` already compares two *populations* and takes its interval
from resampling the runs that made them, so the spread it uses is the observed
spread of the thing being compared:

```kotlin
val before = Runs(List(REPEATS) { measure(kestrel) })
latency.set(slow.inWholeMilliseconds)
val after = Runs(List(REPEATS) { measure(kestrel) })

after.against(before, p99(pay), acceptable = 20.percent).verdict   // Worse
```

and for the pair that did not change, the same call over two sets of runs of an
unchanged target, which must not say `Worse`.

## Why this shape

The interval comes from the runs themselves, so it widens exactly when the
machine is noisy and narrows when it is not — which is what a floor was trying
and failing to approximate from a null step. On a quiet machine the test is
strict; under load it says `CannotTell` rather than inventing a regression. That
is the honest failure mode, and it is the one 0038 was built for.

The cost is runs: a population needs several, and `REPEATS` two-second runs a
side is the price of an answer that means something. This is a `timing`-tagged
test that already runs alone, so the cost is wall-clock in a task nobody puts in
`build`.

The alternative is to keep tuning the floor — a third absolute quantity, a
different percentile. Each is another number measured somewhere other than the
place the claim is made, and 0048 is the evidence that this does not converge.

## Stack

- [x] **`spec-0062-population`** — `RegressionTest` comparing two sets of runs
      through `Runs.against`, and `Floor` used only to skip a machine that can
      resolve nothing.
      Done when: `./gradlew :examples:timingTests` passes ten consecutive times
      on a loaded machine — the count matters, because the present failure rate
      is about one run in five and a single green run proves nothing.
      Measured: 10 of 10, on four cores at load 4.2 to 5.2, with `--rerun` and
      `skipped="0"` on every run, so none of the ten was the assumption
      skipping the test.
- [x] **`spec-0062-said`** — the page saying which kind of answer it is, so a
      single-run comparison stops reading like a verdict.
      Done when: a report from one run against one baseline says it cannot know
      whether the move was real, and one from `Runs` prints the interval.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :examples:timingTests
```

## Open questions

1. **How many repeats?** Five a side is twenty seconds of wall clock for this
    test and is where a bootstrap starts to mean anything; three is the least
    that produces an interval at all. Recommend five, and recording the measured
    failure rate rather than asserting the count is enough.
2. **Does `Floor` still gate the test?** It can honestly say a machine resolves
    nothing at any magnitude, which is worth skipping on. Recommend keeping it
    as an `assumeTrue` about the machine, and removing it from the judgement of
    whether a difference is real.
3. **What does a single-run `Comparison` promise now?** It says what moved and
    cannot say whether the move was real. Recommend the page say so, which is
    the second stack entry, rather than leaving readers to infer a verdict from
    a number that has no interval behind it.
4. **Does this make 0048 wrong or incomplete?** Its diagnosis of `resolution`
    was right and its fix was not sufficient. Recommend leaving 0048 in the tree
    as the record — the absolute figure is still the better of the two floors —
    and letting this supersede its claim about `RegressionTest`.
