# 0004 — The engine

## Problem

Everything is described and nothing runs. A `Simulation` is a value with a
departure schedule and a `RunRecorder` waiting to be filled, and no code turns
one into the other.

The naive version of this is the bug the whole design has been avoiding: spawn
a user, run its steps, sleep the interval, repeat. That paces on responses, so
a slow target quietly lowers the offered load and the tool reports a queue it
made itself.

## Not doing

- No HTTP. A step is whatever `Action` the caller wrote — spec 0005.
- No reporting. `run()` returns a `RunResult`; formatting is 0006 and 0007.
- No closed model, no `pause`, no `loop`, no `doIf`.
- No retries and no connection warm-up. Both change what is measured and need
  their own argument.
- No distributed run. One JVM.

## Shape

```kotlin
import io.github.matthewjones372.proofload.engine.run
import io.github.matthewjones372.proofload.perSecond
import kotlin.time.Duration.Companion.minutes

val result = checkout.at(50.perSecond, over = 1.minutes).run()

result["pay"].responseTime.p99
result.behind.max
```

- `proofload-engine`, a module depending on `proofload-core` and the JDK. It
  carries its own `NoThirdPartyDependenciesTest`.
- `fun Simulation.run(): RunResult` — blocks until the last user finishes.
- One **virtual thread per virtual user**, started by a scheduler at the
  departure time the profile named.
- Recorders are sharded by **carrier** thread, not by virtual thread. A
  `Histogram` is about 43KB and a step keeps two; ten thousand per-user
  recorders is gigabytes. `availableProcessors()` shards, merged at the end.
- A step that fails **abandons that user**. Its later steps are not run and are
  not counted — neither as successes nor as failures.

## Why this shape

Departures come from `InjectionProfile.departures()`, which is a pure sequence
of offsets computed before the run starts. The scheduler's only job is to start
a virtual thread as close to each offset as it can, and to record how close it
got. Nothing in the loop looks at a response before deciding when to send, so
coordinated omission cannot enter through the pacing.

`Thread.sleep` stays out of library code — detekt fails the build on it — not
because a virtual thread cannot afford to park, but because a wait expressed as
a sleep is a wait the tool then measures as the target's latency.

Abandoning a failed user is the honest default. Counting the steps after a
failed login as successes reports a service that answered nobody. The
alternative, carrying on regardless, is what the earlier hand-run example did
and it reported `pay: ok` for a user who never had a cart.

## Stack

- [ ] **`spec-0004-module`** — the `proofload-engine` module, its build wiring,
      its dependency test, and `Simulation.run()` running one user inline.
      Done when: a one-step scenario at one user per second returns a
      `RunResult` with that step's count at 1, and `./gradlew build` is green.
- [ ] **`spec-0004-schedule`** — the scheduler: virtual thread per user,
      departures honoured from the profile, `schedulingDelay` recorded against
      the intended offset.
      Done when: a 50/s run for two seconds starts 100 users, and the first
      result is recorded well before the last — asserted as an ordering, not a
      wall-clock duration.
- [ ] **`spec-0004-shards`** — carrier-sharded recorders, merged at the end,
      and abandoning a user after a failed step.
      Done when: a scenario whose first step always fails reports one count for
      that step and no counts at all for the ones after it.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

Answered by the architect; recorded here so the implementation does not
re-argue them:

1. **The run ends when the last started user finishes**, not when the window
    closes. A user still in flight at the end of the window is finished and
    counted.
2. **A step that throws is a failure with the exception's class name as the
    reason.** The engine is the one place allowed to catch a throwable, because
    an action written by a user is not the engine's bug to crash on.
3. **The scheduler is one platform thread** running `ScheduledExecutorService`.
    It starts virtual threads and never runs a step itself.
