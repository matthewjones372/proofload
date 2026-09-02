# 0024 — Think time and the closed model

## Problem

Two things people expect from a load tool are missing, and both were deferred
until an engine existed. It exists.

**Think time.** A real user reads a page before clicking. A scenario that fires
three requests back to back models a script, not a person, and the concurrency
it produces at a given rate is wrong in a way nobody notices.

**The closed model.** "Fifty users, looping" is how most people describe load,
and Kestrel cannot express it. Refusing outright is defensible only if the tool
explains the trade; refusing silently just sends people back to Gatling.

## Not doing

- No `doIf`, no `loop`, no branching within a scenario. Control flow is its own
  argument.
- No pacing that adapts to the target. That is the closed model wearing a
  disguise.
- No removal of anything: the open model stays the default and the recommended
  one.

## Shape

```kotlin
val checkout = scenario("checkout") {
    exec(browse, api.get("/products"))
    pause(2.seconds)                    // a user reading the page
    exec(pay, api.post("/orders"))
}

val soak = users(50, looping = true, over = 10.minutes)
```

- `Step.Pause(duration)` — a step the scheduler honours by parking the virtual
  thread, recorded as a pause rather than as latency.
- `InjectionProfile.ClosedUsers(count, over)` — a fixed population, each user
  restarting the scenario when it finishes.
- Both a closed run and the page it produces **say what they cannot measure**.

## Why this shape

A pause is a step because everything else is. It has a name and a duration, and
it appears in the report as time nobody was waiting on the target — which is
what stops it being read as latency.

The closed model is the honest hard part. A fixed population that waits for a
response before sending the next request measures a queue of its own making:
when the target slows down, the offered load falls, and the report shows a
service that stayed fast while doing less work. That is coordinated omission,
and it is the reason the open model is the default here.

So it is supported and labelled. A closed run's report carries a line saying
the load was throttled by the target's own responses, and the achieved rate
beside the requested one. Refusing to build it does not stop people needing it;
it stops them using a tool that tells them the truth about it.

## Stack

- [ ] **`spec-0024-pause`** — `pause` as a step, honoured by the engine,
      recorded separately from service time.
      Done when: a scenario with a two-second pause takes two seconds longer
      per user and reports no latency for it.
- [x] **`spec-0024-shape`** — what a closed run reports about its own
      schedule, decided before any of it is built. See *A run with no
      schedule* below.
      Done when: every figure in that section either has a value a closed run
      can honestly produce, or is absent, or is refused where it is written.
- [ ] **`spec-0024-closed`** — `ClosedUsers`, and the engine running a fixed
      population.
      Done when: fifty users produce fifty concurrent journeys, the run reports
      the rate it actually achieved, and nothing in *A run with no schedule*
      prints a number the run did not measure.
- [ ] **`spec-0024-honesty`** — the caveat on the page and in the docs.
      Done when: a closed run's report says the load was shaped by the target,
      and an open run's does not.

## A run with no schedule

Added after the rest of the tool was built, and the reason `spec-0024-closed`
sat unbuilt: almost every number a run now reports about its own schedule
assumes an open model, and a closed run through today's code would print a
lateness figure and a schedule verdict that mean nothing. That is a worse
failure than not having the feature. Each of them, decided:

**The two clocks collapse, and that is the finding.** 0003's service time is
measured from the departure that happened and response time from the one the
profile promised. A closed user's second journey has no promised departure —
the target decides when it starts — so the two are the same number. A closed
run reports one clock and says so. This is not a gap in the implementation; it
is coordinated omission, stated where a reader meets it rather than in a
footnote.

**Absent, not zero.** `behind` and `latePerSecond` are `Timing.none` and empty.
A generator is not late for a departure nobody promised, and zero would read as
perfect punctuality rather than as a question that does not apply.
`heldScheduleFor` and `offered` follow from them and are null, which is what
they already answer for a result with no plan.

**Refused where it is written**, rather than answered meaninglessly:

- a `keptSchedule` goal on a closed simulation, because there is no
  `plannedInterval` to judge against;
- asking a closed profile for its departure offsets, because after a user's
  first journey the target decides when the next one starts;
- `ClosedUsers` inside `then`, `randomized` or `replaying`, because each of
  those shapes departures and a closed model has none to shape.

**Kept, and better here than in an open run.** Little's law (0068) needs no
promised departure: L = λW over what actually happened, and a fixed population
is the case where the L it predicts is a number the caller chose. It becomes
the headline check rather than a footnote. `SteadyState` (0032) is unchanged —
it reads response time second by second and that still exists.

**Kept, relabelled.** `arrivals` (0034) reports the spacing the run produced
and its coefficient of variation. Under a closed model that spacing is the
target's cadence rather than the profile's shape, and the page must say which
it is looking at.

**Refused across the two.** `Plan.unlike` separates a closed plan from an open
one, so `Runs` and `Shards` cannot pool them: they are not one population and a
merged percentile over both is a number about neither. It already did, by
comparing profiles — the closed variant is simply a profile they differ on.

A capacity search needs no refusal after all: `sustainable` takes a scenario
and builds its own rates, so a closed profile has no way to reach one.

The baseline format goes to **version 7**, carrying the population and the
window — the whole of what a closed run asked for. A version 6 file still
reads, and could never have held one, because there was no closed model to
write it from.


## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

Answered by the architect:

1. **A pause parks the virtual thread.** `Thread.sleep` on a virtual thread
    unmounts it rather than holding a carrier, so the detekt ban stays for
    library code and the engine's pause is the one exception, with a comment
    saying why.
2. **A closed run reports achieved rate, not requested rate**, because there is
    no requested rate — the target sets it. The page says so.
3. **Pauses are excluded from `behind`.** The generator is not late for a
    departure that was meant to wait.

Left open by this revision:

4. **What a closed run's `plannedUsers` means.** `ClosedUsers(50, over)` names
    a population, and `plannedUsers` today counts departures. Recommend the
    population, `plannedRequests` absent — it cannot be known before the run —
    and `plannedInterval` zero, which is what makes `lostGround` and `offered`
    fall away by construction rather than by a special case in each.
5. **Does a closed run get a profile shape on the page?** 0019 draws the rate
    line a run asked for. There is none. Recommend drawing the achieved rate
    over time instead, labelled as measured rather than asked for, so the panel
    keeps its place and changes its claim.
