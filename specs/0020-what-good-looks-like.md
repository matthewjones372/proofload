# 0020 — What good looks like

## Problem

The page reports honestly and cannot say whether the run was any good, because
it does not know what good is. A reader gets 302 ms p99 and 2.6% failures and
has to decide alone whether to ship.

Every load tool that answers this without being told invents a threshold, and
an invented threshold is the same lie as an interpolated percentile. The fix is
not for Proofload to guess — it is for the target to be stated once, in Kotlin,
where the test and the report can both read it.

## Not doing

- No default goals. A simulation with none reports numbers, as today.
- No failing a build from the extension. A test asserts; spec 0008 settled that
  and this does not reopen it.
- No comparison against a previous run. That is the next spec, and it needs
  intervals rather than thresholds.
- No goals in an annotation or a config file. A duration belongs in Kotlin.

## Shape

```kotlin
import io.github.matthewjones372.proofload.expecting
import io.github.matthewjones372.proofload.failureRate
import io.github.matthewjones372.proofload.keptSchedule
import io.github.matthewjones372.proofload.p99
import io.github.matthewjones372.proofload.percent

val simulation = checkout.at(50.perSecond, over = 1.minutes).expecting(
    p99(pay) under 200.milliseconds,
    failureRate under 1.percent,
    keptSchedule,
)

result.metEveryGoal shouldBe true
```

On the page, above everything:

> **2 of 3 goals met.**
> `/pay` p99 was 302 ms against a 200 ms target — **51% over**.

- `Goal` — a sealed value: a percentile under a limit, a failure rate under a
  share, or the schedule being kept.
- `Verdict` — a goal, whether it was met, and by how much it was missed.
- `Simulation.expecting(...)`, carried in the `Plan` so the report has it.
- `RunResult.verdicts` and `metEveryGoal`.

A percentile goal reads **response time** unless it says otherwise, because
that is what a user would have experienced. `p99(pay, of = ServiceTime)` asks
about the target alone.

## Why this shape

The goal is a value, like everything else here, so the same declaration drives
the assertion in the test and the verdict on the page — and a report that
disagrees with the build becomes impossible rather than merely unlikely.

Reporting how far a goal was missed matters more than the pass or fail. "51%
over" tells a reader whether they are looking at a tuning problem or a design
problem; a red cross tells them nothing they can act on.

Defaulting to response time is the honest default: a goal written against
service time can be met by a generator that never sent the load.

## Stack

- [ ] **`spec-0020-goals`** — `Goal`, `Verdict`, the `under` DSL, `expecting`,
      and evaluation on `RunResult`.
      Done when: a goal that is met and one that is missed both report the
      margin, and a simulation with no goals has no verdicts.
- [ ] **`spec-0020-page`** — the verdict block at the top of the report.
      Done when: a run with goals leads with how many were met, a run without
      shows no verdict block, and the golden holds both.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

Answered by the architect:

1. **A goal against a step that never ran is missed, not skipped.** A step that
    did not run is a stronger failure than a slow one, and a verdict that
    quietly disappears is how a broken scenario passes.
2. **`failureRate` is over the whole run** unless given a step. Per-step is the
    same value with a name attached.
3. **Margins are reported as a percentage of the target**, not in absolute
    units: 51% over reads the same whether the target is 200 ms or 2 s.
