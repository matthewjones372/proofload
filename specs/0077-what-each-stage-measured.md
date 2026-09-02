# 0077 — What each stage measured

## Problem

`then` puts two rate lines in `Stages` so a run ramps, holds, and ramps again
(`InjectionProfile.kt:50`). The report then gives one p50, one p95, one p99 for
the whole thing. A run that ramped 100/s to 1,000/s and held reports a p99 over
a population that was never offered at any one rate: it is a mixture of the
easy start and the hard end, weighted by however long each lasted. Nobody asked
that question and no target answers it.

Worse, the number moves for reasons that are not the target. Lengthen the ramp
and the aggregate p99 falls, because more of the samples were taken while the
load was light. A team tuning a ramp would read that as an improvement.

What they do today is read the timeline chart by eye and guess where the stage
boundaries were, because nothing on the page draws them.

## Not doing

- **No new recording.** No histogram per stage on the timed path: that is
  steps × stages of them, and 0003 sized one recorder deliberately.
- **No new baseline format.** Everything needed is already written — the
  timeline since version 6 and the rate lines since version 3.
- **No per-stage goals.** A `Goal` is judged against a run. Judging one per
  stage is a different feature and wants its own verdict shape.
- **No splitting a second.** A boundary that falls inside a second is reported,
  not interpolated: a second is the finest thing the timeline holds.
- **Nothing for a run with one stage**, which is every run that is not staged.

## Shape

```kotlin
/** One stage of a staged run, and what the run measured while it ran. */
data class Stage(
    val index: Int,
    val of: Int,
    val profile: InjectionProfile,
    /** The whole seconds of the timeline this was summed over. */
    val from: Duration,
    val until: Duration,
    /** What the profile asked for, which differs where a boundary fell inside a second. */
    val planned: Duration,
    val serviceTime: Timing,
    val responseTime: Timing,
    val ok: Long,
    val failed: Long,
)

/** The run split at the boundaries its profile named; empty where nothing staged it. */
val RunResult.stages: List<Stage>

result.stages[1].serviceTime.p99   // the hold's p99, not the ramp's and the hold's
```

- Derived, not recorded: each stage is the timeline's seconds between its
  boundaries, merged. `Timing.precision` (0047) comes with them and says what
  they are good to, which is the timeline's width and not a step's.
- Empty for a profile that is not `Stages`, and for a run with no timeline —
  a version 5 baseline, or a result built from samples.
- Both reports gain a stage table where `stages` is non-empty, naming each
  stage's rate line, its window, its counts and its two clocks.

## Why this shape

**Derived rather than recorded.** The alternative is a recorder per stage,
which is the honest full-precision answer and costs steps × stages histograms
at 43KB each on the path this tool tries hardest not to allocate on. The
timeline already holds every second of the run at 0047's coarse width, and a
stage is a contiguous run of seconds. So the number exists; it is one
significant digit rather than two, and it says so. Recommend derived: a
p99 good to 12% that answers the right question beats one good to 3% that
answers the wrong one.

**A boundary inside a second is named, not split.** Stage windows are
arbitrary durations and the timeline is whole seconds. A second straddling a
boundary is counted in the stage its start falls in, `from`/`until` report the
whole seconds actually summed, and `planned` reports what was asked for. Where
they differ the page says which second went where, rather than a reader
assuming an exact cut. The alternative — refusing to report stages unless every
boundary lands on a second — would refuse a 2.5-second ramp, which is a
legitimate thing to write.

## Stack

- [x] **`spec-0077-stages`** — `Stage`, `RunResult.stages`, the boundary
      arithmetic and the misalignment it reports.
      Done when: a three-stage profile splits a timeline into three windows
      whose seconds add up to the run's, a boundary inside a second lands in
      the earlier stage with `planned` differing from `until - from`, and an
      unstaged run answers an empty list.
- [x] **`spec-0077-page`** — the stage table in both reports, and the note that
      names the precision it is at.
      Done when: a staged run's page carries a row per stage with its rate line
      and both clocks, an unstaged run's page is unchanged, and the goldens move
      once with the diff read rather than regenerated.
      **Service time only, and the summary names an ordinal.** Two deviations,
      both to keep the table narrow. The rows carry service time: response time
      would double every column, and a stage's own backlog is what `behind`
      already answers for the run. And the GitHub summary names a stage `1 of
      2` rather than by its rate line — that summary is a PR comment and stays
      terse, the shape it names is on the page, and a ramp printed as its start
      rate would name the stage after the load it left behind. The page itself
      carries the rate line, which is where a reader matching a row to a shape
      is looking.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

A staged run's report shows each stage's own percentiles, and they differ from
each other and from the aggregate.

## Open questions

1. **Does a `Randomized(Stages(...))` split?** The wrapper keeps the shape
    underneath, so the boundaries are still there. Recommend unwrapping it —
    jitter moves departures within a window, not the windows.
2. **Do nested `Stages` flatten?** `then` on a `Stages` could nest. Recommend
    flattening to the leaves, a stage being a rate line rather than a tree.
3. **Should the aggregate percentile stay on the page for a staged run?** It is
    the number this spec calls a mixture of populations. Recommend keeping it
    and labelling it, rather than removing a number people compare between
    builds; the stage table beside it is the correction.
