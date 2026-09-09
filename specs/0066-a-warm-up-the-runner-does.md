# 0066 — A warm-up the runner does

## Problem

Warm-up here is manual discipline, written five times and never the same way
twice: `RegressionTest.kt:105` throws a run away by hand, `ReportShowcase.kt:75`
drops eight seconds before measuring thirty, `CapacitySearchTest.kt:72` warms at
10/s for a ladder running at 4/s to 40/s, `Ceiling.kt:57` warms every row, and
`Calibration.kt:44` drops two windows of eight. None of it is in a value or on a
page, so nothing tells a warmed run from a cold one and `Runs` pools the two.

The cost is not tidiness. On an idle machine a 45/s run reported `behind.p99` of
**612 ms** and `keptSchedule` **missed**, from a cold JVM's first departures
alone; behind a throwaway warm-up it reported **897 µs** and **met**. Class
loading decided the goal that says the rest of the numbers mean what they say.

**0032 cannot fix this.** Detection finds where a run settled after the fact;
warm-up is a phase before the measurement whose samples never exist.
`KeptSchedule.overSteadySegment` is `false` (`Goal.kt:134`) and `steady` sets
`behind = Timing.none` (`SteadyState.kt:72`), both because the timeline never
kept lateness second by second: the 612 ms cannot be narrowed away, only not
paid. `lostGround()` voids a rung on the same figure, which is why 0043's
deferral to 0032 is still open. They are complements: the caller declares the
length, and `steadyState` over the measured window audits it.

## Not doing

- **No guessing the length.** No JFR, no `CompilationMXBean`: that is the
  estimate `[WARMUP-JMH]` put at a median error of 28 s, right in 19% of forks.
- **No warm-up at another rate or another scenario.** Warming at 10/s for a run
  at 45/s warms the wrong code paths and sizes the wrong pool.
- **No recording it and hiding it.** Excluded means never recorded: no
  `result.warmUp`, no flag on a `Second`, nothing to filter out later.
- **No default.** A tool that warms unless told otherwise changes what every
  existing run measures, for a length nobody declared.
- No change to `SteadyState`, and none to `calibrate()`: its two dropped runs
  measure the machine's floor, a different question.

## Shape

```kotlin
val result = proofload.run(
    checkout.at(45.perSecond, over = 2.minutes).warmingUp(5.seconds),
)
result.plan.warmUp    // WarmUp(5.seconds), answerable before a request leaves
```

`warmingUp(5.seconds)`, not `warmingUp(for = ...)`: `for` is a hard keyword and
would need backticks at every call site.

- `WarmUp(over: Duration)` on `Simulation`, carried into `Plan` and compared by
  `Plan.unlike`, so `Runs` refuses a warm set and a cold set as one population.
- The rest is derived: the same arms, each held at the rate it opens at, needing
  `InjectionProfile.startRate` — `endRate`'s mirror, `InjectionProfile.kt:108`.
- The page gains "Warmed for 5 s at 45/s, not counted", 0064's opening line
  names it inside the schedule, and its ticks read `warming up` until measuring.

## Why this shape

A value rather than two `run()` calls, for three things the hand-written version
cannot reach: the plan can say what was warmed, so the report and `Plan.unlike`
can; 0064's countdown can include it; and it sits inside one hold of 0050's
lock, since `Exclusive` wraps `Engine.run` (`Exclusive.kt:30`) where two calls
are two acquisitions with a gap another process may take. Excluded rather than
discarded must then be true in the code: the warm-up departs through a
`StepSink` (`Recorders.kt:18`) that drops every record and a discarded
`Departed`, so no histogram, second or lateness sample reaches `behind`.

The length is the caller's because the caller knows the deploy: two hundred
connections to a JVM is seconds, an in-process null step is milliseconds.
`[WARMUP-JMH]` and `[WARMUP]` show a *detector* cannot name the moment, not that
a length cannot be stated; stated, it is part of the experiment, printed and
never claimed sufficient, and `[REPLICATION]` treats discarding the first
invocation as method rather than setting.

The alternative is a stage on the profile — `warmUp(5.seconds) then hold(...)`.
Rejected: every consumer of `InjectionProfile` counts users, draws the shape and
computes the window from it, so an excluded stage is a special case in each.

## Stack

- [x] **`spec-0066-value`** — `WarmUp`, `warmingUp`, `Plan.warmUp`,
      `InjectionProfile.startRate`, and `Plan.unlike` comparing it.
      Done when: a plan says what it warms and for how long before a request
      leaves, and two plans differing only in it are not one population.
- [x] **`spec-0066-unrecorded`** — the engine running the warm-up into a sink
      that records nothing, before the measured clock starts.
      Done when: a target that counts requests shows it was hit while warming,
      and `startedAt`, the counts, the timeline and `behind` hold nothing of it.
- [x] **`spec-0066-said`** — the warm-up on the page, in the markdown, and in
      the lines 0064 prints.
      Done when: a warmed page says how long, at what rate, and that it was not
      counted; an unwarmed run says nothing at all.
- [x] **`spec-0066-rungs`** — `Search.warmingUp`, each rung warmed at its own
      rate, `worstCase` counting them, and the five hand-rolled throwaways gone.
      Done when: `worstCase` includes a warm-up per rung and per bisection, and
      `CapacitySearchTest` finds its rate with no throwaway of its own.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :examples:timingTests
```

plus a `timing` test of the claim above: 45/s cold, then behind
`warmingUp(5.seconds)`, `keptSchedule` missed and then met.

## Open questions

1. **What rate warms a ramp?** Recommend the rate it opens at — the departures
    otherwise paid cold; the peak warms it into a state the measurement inherits.
2. **Does it wait out its users first?** Recommend yes — one still in flight is
    load the plan did not name; the gap is the target's, so it reads `draining`.
3. **Once per search, or once per rung?** Recommend per rung at its own rate: a
    rung is judged on its own schedule, and one warm-up leaves rung 1 cold.
4. **Does every run of a `Runs` set warm?** Recommend yes: identical treatment
    makes them one population `[REPLICATION]`, retiring `Runs.first`'s KDoc.
5. **Should the page say when a warm-up looks too short?** Recommend it where
    the steady-state note prints — the caller's only feedback on their number.
