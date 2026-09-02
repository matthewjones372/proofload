# 0076 — A run that fell behind is still a measurement

## Problem

When the generator falls behind, the page says so — *Behind schedule, 41 ms
late at p99* — and the cookbook's advice is "a lower rate or a bigger machine".
That is a diagnosis with no treatment. The reader has three questions and the
run answers none: what load actually left, when the schedule was lost, and
whether anything on the page is still true about the target.

The last is the waste. Every request carries two clocks (0003): service time
from the departure that happened, response time from the one the profile
promised. Response time is what a fell-behind run corrupts. Service time is a
true measurement of the target at whatever load reached it — and the page,
which already defaults to service time, never says so, so a voided run is
thrown away with a good measurement inside it.

Nothing measures the load that left. `arrivals` records `departure.offset`,
the schedule as offered; `behind` is one histogram over the whole run, so the
second it was lost is unrecoverable; and `plan` only knows what was asked.

## Not doing

- **No throttling.** A run that lowers its own rate to keep schedule measures a
  load it then does not report, which is the lie 0003 exists to stop. A search
  (0031) is where adaptation lives, one labelled run per rung.
- No change to `fellBehind()` or `lostGround()`; 0043 settled both gates.
- No change to which clock a goal reads. A response-time goal on a run that
  fell behind fails, and it should: the failure is the finding.
- No pre-flight ceiling. Asking what a machine can drive before running is a
  different measurement, minutes long, and a spec of its own (question 4).
- No per-step lateness. Departures are late per user, not per step.

## Shape

```kotlin
result.offered            // Offered(asked = 2_500/s, left = 1_910/s, over = 26.2s)
result.heldScheduleFor    // 11s — the seconds before the first that lost ground
result.latePerSecond[12].p99     // lateness of the departures that second
```

- `Offered(asked: Rate, left: Rate, over: Duration)` — derived at read time from
  `plan.plannedUsers`, `plan.plannedWindow` and the timeline's count and span.
  Nothing new is stored: a number that can be derived is derived.
- `RunResult.latePerSecond: List<Timing>` — one coarse table per second of the
  run, recorded from `schedulingDelay` beside the seconds already kept. At run
  level rather than on `Second`, which is also every *step's* timeline: a
  departure is late once, for the user, not once per step that user makes.
- `heldScheduleFor` — the span before the first second whose lateness p99
  exceeds `plan.plannedInterval`, which is `lostGround()`'s rule read second by
  second. The whole window when no second did.
- The *Behind schedule* paragraph, on the page and in the job summary, says:
  *asked for 2,500/s; 1,910/s left over 26 s rather than 20; the schedule held
  for 11 s. Response times include that backlog. Service times are the target
  at 1,910/s.* A lateness series joins the timeline so the reader sees the
  second it went.
- A void rung (0031) reads the same: *not judged — 1,910/s left, service p99 84
  ms at that load.*

## Why this shape

Three things this could say. The rate that left is a fact from counts and a
span. The second it was lost is a fact only if lateness is kept per second,
which is one more 5,384-byte coarse table per second of the run, at run level
and not per step — 0049 paid three times that for response time on every step.
The third, "re-run at 1,900/s", is advice; the page prints the two facts and
lets the reader draw it, because the rate that held under a ramp is the
profile's rate at that second, which is a fourth number and a later one.

`Offered` is a value rather than three properties so the page, the markdown and
the JSON print one thing. It is derived rather than frozen because the plan and
the timeline are already on the result and a stored copy could disagree.

## Stack

- [x] **`spec-0076-offered`** — `Offered` and `RunResult.offered` in core.
      Done when: a result whose plan asks 2,500/s over 20 s and whose timeline
      counts 50,000 over 26 s reads `asked` 2,500, `left` 1,923, `over` 26 s,
      and a result with no plan or no timeline reads nothing rather than zero.
- [x] **`spec-0076-lateness`** — `Second.behind` recorded on the run's seconds,
      `heldScheduleFor`, both in the JSON.
      Done when: a recorder fed lateness only from second 11 on reads
      `heldScheduleFor` of 11 s, a step's second reads `Timing.none`, and
      `:benchmarks:ceiling` is unchanged.
- [x] **`spec-0076-page`** — the paragraph on both sinks, the lateness series
      on the timeline, the cookbook's "Did the generator keep up?" answered.
      Done when: the showcase report's behind paragraph names asked, left and
      held, the golden markdown carries the same line, and a run that kept
      schedule shows none of it.
- [x] **`spec-0076-rung`** — the void rung line and `CapacityPage` reading.
      Done when: a void rung names the rate that left and the service p99 at
      it, and a judged rung is unchanged.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :examples:timingTests   # ReportShowcase writes the paragraph
```

## Open questions

1. **Where does per-second lateness live?** ~~Recommend `Second.behind`.~~
    Settled the other way when built: a parallel `List<Timing>` on the result,
    because `Second` is also every step's timeline and the field would mean
    nothing in most instances of it. `steady` (0032) does not narrow it yet.
2. **The rate held under a ramp.** `heldScheduleFor` is exact; the rate at
    that second needs the profile's rate at an offset, which nothing exposes.
    Recommend printing the time now and the rate when 0014's profile shapes
    grow a `rateAt`.
3. **Does `left` count a completion step's answers?** `arrived` lands on the
    timeline where it was answered. Recommend counting departures only, from
    the steps that have a `schedulingDelay`, so `left` is what this process
    sent.
4. **A ceiling before the run.** 0056 measures one as a benchmark; a
    `kestrel.ceiling()` beside `calibrate()` that refuses a rate above it is
    the remedy that stops the waste before it starts. Recommend its own spec.
