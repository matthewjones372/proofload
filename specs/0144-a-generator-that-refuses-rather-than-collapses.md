# 0144 — A generator that refuses rather than collapses

## Problem

Petshop's heavy load test (`docs/heavy-load.md`, petshop#80) ramped one laptop
to 50,000 req/s, and three times the generator fell over before the shop did.
The JDK client used up the ephemeral ports; Jetty's queue left 120,000 virtual
threads waiting on one lock; a timed `Semaphore` left 40,000 waiters whose
timeout cleanup kept every carrier busy. Each time the injector took five
cores and then its 4 GB heap while the target sat idle.

The cause is the same each time. `Scenario.depart` (`VirtualThreads.kt:369`)
starts a virtual thread for every arrival that falls due, whatever is already
running. Once the target falls behind, each waiting user is a thread, a stack
chunk and a place in someone's queue, so the cost of the backlog lands on the
generator. `ranOutOfRoom()` catches the ports case after the fact; nothing
catches the heap or the carriers, and nothing stops the backlog growing.
Petshop now works around it in its own client (`NoFreeConnection`, a
`tryAcquire` that fails at once), which every user would have to rediscover.

## Not doing

- **No queueing or deferral.** An arrival held back until a slot frees is
  coordinated omission (0122, invariant 1). Refused is the only option.
- **No default cap.** Unset means today's behaviour exactly.
- **No cap for closed models.** A population is its own cap.
- **No cheap-failure path.** `Action.attempt` already records `Threw` by class
  name with no message or stack trace (`VirtualThreads.kt:735`). The
  `Throwable.toString` petshop profiled came from client libraries building
  their exceptions, which the engine cannot reach.

## Shape

```kotlin
scenario.at(50_000.perSecond, over = 2.minutes)
    .inFlightAtMost(20_000)

result.arrivals.refused        // arrivals due while 20,000 were in flight: no thread, no request
result.generatorSaturated()    // refused > 0 || ranOutOfRoom() || lostGround()
```

- The check runs on the scheduler thread at the due instant, against a counter
  of threads **started** and not finished. `Departures.running` counts mounted
  users, which is too late: an unmounted thread is the cost being bounded.
- A refused arrival is counted, never recorded as a step and never as the
  target's failure. It keeps its user number, so a feeder does not shift.
- `refused` per slot sits beside `usersInFlight` on the timeline.
- HTML and markdown print "not sent: generator saturated, N" beside failures
  whenever N > 0, and the run's verdict reads void with that reason, the way
  a rung that ran out of room does (`Progress.kt:322`).

## Why this shape

0122 listed "no dropping" under Not doing and made "every requested arrival
is booked exactly once" an invariant. This spec reopens that on purpose, and
only behind an opt-in. `docs/invariants.md` already states invariant 2 as
"sent or counted as not sent", so a counted refusal that voids the run keeps
it; only a silent one would break it. The alternative, leaving every
arrival in and letting `ranOutOfRoom` explain the wreck afterwards, gives no
result at all once the heap goes. The invariant becomes `requested =
departed + refused + dropped`.

## Stack

- [ ] **`spec-0144-cap`** — `Simulation.inFlightAtMost(n)`, the started counter,
      the refusal on the scheduler thread, `Arrivals.refused` and per slot.
      Done when: a target that blocks until released, at a cap of 10, reads
      exactly 10 started and every later arrival refused, with no clock in the
      test (0124), and an uncapped run reads `refused == 0`.
- [ ] **`spec-0144-void`** — `generatorSaturated()`, the void verdict and its
      reason in `Search` and `Progress`, and both reports' line.
      Done when: goldens for a capped run show the line and the void reason,
      and a run with `refused == 0` moves no golden.
- [ ] **`spec-0144-docs`** — `docs/measurement.md` gains "refused arrival"
      and when to set a cap; `docs/invariants.md` invariant 2 names the
      counter; `docs/goals-and-results.md` lists `refused` and the verdict.
      Done when: invariant 2's prose names `arrivals.refused` and no page
      says every scheduled arrival is started.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :examples:timingTests
```

## Open questions

1. **Does one refusal void the run, or a share?** Recommend one. A capped run
   that refused anything did not offer the profile it promised.
2. **Should hiccups over a threshold also make `generatorSaturated()` true?**
   Recommend not yet: 0126 owns carrier starvation and would set its own gate.
3. **Per arm, or per simulation?** Recommend per simulation: the heap and the
   carriers are shared, so one budget is what is being protected.
4. **Name.** `inFlightAtMost` reads like `keepingSlots`; `refusingAbove` says
   what happens past it. Recommend `inFlightAtMost`.
