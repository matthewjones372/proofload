# 0034 — Arrivals that are not a metronome

## Problem

`atRate` puts the nth departure at exactly n divided by the rate. That is
exactly right for drift, and it produces traffic no target ever receives: an
inter-arrival coefficient of variation of zero, a metronome.

Queueing delay scales with the variability of arrivals, not only with their
mean, so an even generator understates queueing at the rate it claims to be
testing. Real session arrivals are close to Poisson, and the burstiness does not
average away as users are added `[POISSON]`. The consequence for this tool is
narrow and specific: a p99 measured under even arrivals is optimistic against
the same mean rate in production, and nothing on the page says so.

## Not doing

- No self-similar or bursty models. Heavy-tailed arrival processes are a real
  thing to want and a different spec.
- No traffic replay, no arrival profile imported from production. Same.
- No change to the default. Even spacing stays the default or every assertion
  anyone has written moves at once.
- No randomised anything else. Feeders are 0015's and are deliberately a
  function of the user's number.

## Shape

```kotlin
val soak = hold(200.perSecond, over = 10.minutes).randomized(seed = 20260826)

soak.userCount()                 // unchanged: the count is exact, the spacing moves
result.arrivals.cov              // 0.98, what was actually produced
```

- `InjectionProfile.randomized(seed)` — a wrapper value, so a randomised shape
  is still a shape that can be compared, counted and printed.
- `RunResult.arrivals` — the achieved inter-arrival mean and coefficient of
  variation, beside `behind`.

## Why this shape

Two ways to generate it. Accumulating exponential gaps is the textbook Poisson
process and it drifts, needs an accumulator, and lets the last departure fall
outside the window the profile promised. The alternative uses the fact that a
Poisson process conditioned on N arrivals in a window has those arrivals
distributed as N sorted uniforms: draw N per window, sort, done. The count stays
exact, every departure stays inside the window, `userCount()` still answers
before the run, and the sequence stays reproducible from the seed.

Recommend the second, generated a window at a time so `departures()` stays lazy
and a ten-minute run does not sort six hundred thousand doubles at once.

Reporting the achieved coefficient of variation is the point of the feature as
much as producing it. The standard advice for driving a load tool this way is to
measure the inter-arrival CoV you actually produced and confirm it is near 1.0
`[COV]`, and Kestrel is in a position to do that for the user rather than
telling them to.

## Stack

- [x] **`spec-0034-randomized`** ([#4](https://github.com/matthewjones372/kestrel/pull/4)) — the wrapper, window-at-a-time generation, and
      `userCount` and `over` unchanged through it.
      Done when: a randomised hold departs the same number of users inside the
      same window, two runs with one seed produce identical offsets, and two
      seeds do not.
- [x] **`spec-0034-reported`** ([#9](https://github.com/matthewjones372/kestrel/pull/9)) — the achieved mean and CoV on the result and the
      page.
      Done when: an even profile reports a CoV near zero, a randomised one near
      one, and the page names which was asked for.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Should the seed have a default?** Recommend no. An unseeded random run is
    not reproducible, and this repository has been deliberate about
    reproducibility since feeders.
2. **How does it compose across stages?** Recommend per stage, seeded by the
    seed plus the stage's index, so a ramp followed by a hold does not repeat
    the same draws and the whole shape stays a pure function of one seed.
3. **Should randomised become the default later?** Recommend revisiting once the
    achieved CoV is on the page: the honest default is the one whose consequences
    are visible, and today they would not be.
4. **Does the report warn when even spacing was used?** Recommend one line, not
    a warning: even arrivals understate queueing, and a reader comparing against
    production should know which they measured.
