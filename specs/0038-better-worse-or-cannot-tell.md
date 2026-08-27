# 0038 — Better, worse, or cannot tell

## Problem

0021 compares two runs by asking whether their sampling intervals overlap, which
is right about one run's precision and silent about the machine. On a shared
runner the machine is most of the movement: measured coefficient of variation on
GitHub-hosted runners is about 2.66%, so a 2% gate fires falsely roughly 45% of
the time and about 7% is needed for one `[CINOISE]`. An independent study over
sixteen days saw ratios from 0.51 to 1.36 `[CINOISE-2]`.

0030 refuses to compare across a different machine, and 0037 gives several runs
of the same one. What is still missing is the statement those two make possible:
not "302 against 290", but **B is 4% slower than A, plus or minus 1.5%**, and,
when the runs will not support it, **this cannot tell**.

That third outcome is the feature. Everything else in this repository refuses to
print a number nobody measured; a comparison that always returns better or worse
is the last place where one is printed anyway.

## Not doing

- No p-values, no significance test as the headline. With enough samples any
  difference becomes significant, which is why the effect size and its interval
  are what get reported `[EFFECTSIZE]`.
- No change-point detection over a history. That needs thirty days of results
  `[HUNTER]` and is a different feature.
- No automatic gating. Whether a build fails stays the team's call, as 0021 and
  0030 both settled.
- No modelling of the runner. 0039 measures it; this spec consumes what it
  measured.

## Shape

```kotlin
val comparison = candidate.against(baseline, p99(pay))

comparison.ratio        // 1.04
comparison.interval     // 1.015 .. 1.065
comparison.verdict      // Worse / Better / CannotTell

comparison shouldBe NotWorseThan(3.percent)
```

and on the page:

> `/pay` p99 is **4% slower** (1.5% to 6.5%, 10 runs each). Worse than the 3%
> that was declared acceptable.

or

> `/pay` p99 is **4% slower** (−2% to 10%, 3 runs each). **Cannot tell**: the
> interval spans the 3% that was declared acceptable. More runs would narrow it.

- `Comparison` over two `Runs`, for any statistic a `Timing` can produce.
- A practical threshold the caller declares. The verdict is the interval against
  that threshold, not against zero.

## Why this shape

The interval comes from a bootstrap over the per-run values rather than a
parametric formula: performance distributions are skewed and multimodal, which
is the assumption a parametric interval would be making `[SHAPE]`, and the
bootstrap needs nothing but the values 0037 already keeps.

Judging against a declared threshold rather than against zero is what makes the
three outcomes possible, and it puts the number in the only place that knows it:
a team that cares about 1% and a team that cares about 10% are asking different
questions of the same data.

Refusing under a handful of runs is deliberate. A bootstrap over three values is
arithmetic wearing a lab coat.

## Stack

- [x] **`spec-0038-interval`** ([#31](https://github.com/matthewjones372/kestrel/pull/31)) — the bootstrap over `Runs`, and `Comparison`
      with its three verdicts.
      Done when: two sets of runs with a known injected difference report an
      interval containing it, identical sets report an interval containing 1.0,
      and fewer than five runs on either side reports `CannotTell` with that as
      the reason.
- [x] **`spec-0038-page`** ([#35](https://github.com/matthewjones372/kestrel/pull/35)) — the comparison on the report and the assertion in
      the test frameworks.
      Done when: the golden holds all three verdicts and each says what would
      change it.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **How many runs are enough?** Recommend refusing under five per side and
    saying so, rather than producing an interval so wide it is useless.
2. **Which statistic by default?** Recommend p99 response time, matching 0020's
    default, with any `Timing` accessor available including 0033's goodput.
3. **Does an inconclusive comparison fail a test?** Recommend no by default,
    with an opt-in for teams that want it. A test that fails on "cannot tell"
    fails on a noisy Tuesday, and gets deleted on the Wednesday.
4. **Bootstrap resample count?** Recommend ten thousand, which is milliseconds
    over ten values and removes the question from the reader's mind.
