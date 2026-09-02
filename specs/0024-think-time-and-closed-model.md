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
- [ ] **`spec-0024-closed`** — `ClosedUsers`, and the engine running a fixed
      population.
      Done when: fifty users produce fifty concurrent journeys, and the run
      reports the rate it actually achieved.
      **Wants a revision before code, not a bigger effort.** This spec predates
      most of what a run now reports about its own schedule, and every one of
      those numbers assumes an open model: `behind` and `latePerSecond` measure
      lateness against a departure the profile promised, and a closed user has
      no promised departure after its first; `keptSchedule` and `lostGround`
      judge a run against `plannedInterval`, which a fixed population does not
      have; `offered` and `heldScheduleFor` (0076) compare what left against
      what was asked. A closed run through today's code would put a lateness
      figure and a schedule verdict on the page that mean nothing, which is a
      worse failure than not having the feature. What each of those should say
      under a closed model is a decision for this spec to make, and the
      honesty entry below is not enough on its own.
- [ ] **`spec-0024-honesty`** — the caveat on the page and in the docs.
      Done when: a closed run's report says the load was shaped by the target,
      and an open run's does not.

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
