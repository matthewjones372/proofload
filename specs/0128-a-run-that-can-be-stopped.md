# 0128 — A run that can be stopped

## Problem

A Proofload run cannot be stopped. `Engine.run(simulation)` blocks on
`Departures.awaitAll()`, a `CountDownLatch` with no timeout, and there is no
cancel, no close, no deadline and no lifecycle anywhere in core or the engine.
The consequences are not theoretical:

- A step body that never returns hangs the run for ever. `during` and `repeat`
  loops are checked against their own clock and not against the run's, so a
  scenario can outlive its profile's window by any amount.
- A target slower than the run (0123's experiment 7) blocks past `over` with no
  bound, and the `Drain` window closes at the *planned* end regardless.
- Ctrl-C, a JUnit timeout, or a CI job cancellation kills the JVM with the
  result unfrozen. Everything the run measured is lost, and there is no
  shutdown hook to write it.
- `Proofload.run` is called from a test thread; if JUnit interrupts it, the
  `await` throws `InterruptedException` out of the engine, `scheduler.shutdownNow()`
  runs in the `finally`, and the run's virtual threads keep going against a
  target nobody is watching, writing into recorders nobody will freeze.

There is no `STOPPING`. There is `RUNNING` and there is a JVM.

## Not doing

- **No `Closeable` engine and no thread pool the caller owns.** The engine's
  threads are its own and are daemons; that stays.
- No coroutine or reactive API. A run is a blocking call by design, and the
  cancellation handle is a value, not a change of colour.
- No cancellation of the *target's* work. Proofload can stop sending and stop
  waiting; it cannot un-send.
- No partial result written to disk on JVM exit. That is a sink's decision, and
  a half-written report is worse than none.

## The lifecycle, stated

```
CREATED ──start──▶ RUNNING ──window ends, or stop()──▶ STOPPING ──▶ COMPLETED
                      │                                              ▲
                      └────────── FAILED (engine bug, Error) ────────┘
```

- **CREATED** — the `Simulation` value. Nothing has departed. Refusals that can
  be made here are made here: `requireSeededThinking`, the step-name clash, a
  `keptSchedule` goal on a closed run. Already true.
- **RUNNING** — the pump is booking, users are departing.
- **STOPPING** — no further arrival is booked. Booked-and-not-departed arrivals
  are **dropped and counted**. In-flight users are given a stated grace, then
  abandoned and counted.
- **COMPLETED** — the recorders are frozen. Always reached, on every path,
  including cancellation: a cancelled run produces a `RunResult`, and that
  result is `Invalid(Cancelled(...))` per 0121.
- **FAILED** — an engine invariant broke. Reached only where a result would be a
  lie; today `Recorders.freeze`'s `checkNotNull` is the one path here and it
  throws away everything rather than reporting.

## Shape

```kotlin
val run = proofload.start(checkout.at(50.perSecond, over = 10.minutes))
run.stop(grace = 5.seconds)          // idempotent, returns the frozen result
val result = run.await()             // the same value, however it ended

// and the guard against the hang, declared on the run rather than hoped for:
proofload.run(simulation.stoppingAfter(over * 2))
```

- `RunHandle`: `stop(grace)`, `await()`, `state`. `run(simulation)` becomes
  `start(...).await()`, so nothing existing changes shape.
- `Simulation.stoppingAfter(deadline)` — a wall bound past the profile's window,
  so a scenario with a loop, a slow target or a hung body ends as a measurement
  rather than as a hang. **No default**, for the reason `drainingFor` has none:
  a bound chosen for the caller turns "the target never answered" into "we did
  not wait", and telling those apart is the point.
- A JVM shutdown hook that moves the run to STOPPING and freezes, so a Ctrl-C
  produces a `RunResult` marked `Cancelled` instead of nothing.

## Guarantees about in-flight work

Stated so a test can hold them:

1. `stop()` is idempotent and safe from any thread, at any state. The second
   call returns the same result value as the first.
2. Cancellation never produces a result indistinguishable from a complete run:
   `Cancelled` is a `Doubt`, `metEveryGoal` is false, and the JSON headline says
   so before it says anything else.
3. No sample recorded before `stop()` is lost. Freeze happens after the grace,
   and a sample arriving after freeze is refused at the seam rather than
   destroying the result (0125).
4. Dropped and abandoned arrivals are counted separately: `dropped` never
   departed, `abandoned` departed and did not finish. Neither is a failure of
   the target and neither is recorded as one.
5. No thread outlives the run. The scheduler, hiccup, room and progress
   executors are shut down on every path, and a test asserts the JVM's thread
   count returns to its pre-run value.
6. Resources the *caller* owns — an HTTP client, a `DataSource`, a Kafka
   producer — are the caller's to close. Proofload closes what it opened and
   says so, rather than closing what it was handed.

## Adversarial cases

| Case | Must produce |
|---|---|
| stop while the pump is booking | remaining arrivals `dropped`, none `missed`, result frozen |
| stop while a request is in flight | grace observed, then `abandoned`; no half-written session recorded |
| stop during a step's own timeout | the step's own reason, not a cancellation reason |
| stop during the `Drain` window | `unmatched` and `inFlight` distinguished as they are today |
| stop twice, from two threads | one result, no exception |
| stop, then the JVM exits | the hook is a no-op; the run already completed |
| JVM exit with work outstanding | a frozen `Cancelled` result, or nothing — never a `Valid` one |
| a body that never returns | `stoppingAfter` fires; the body is abandoned, the run completes |
| `Error` out of a body | FAILED, and 0125's question 1 |

## Stack

- [ ] **`spec-0128-state`** — `RunState`, `RunHandle`, `start`/`await`, with
      `run` implemented over them and no behaviour change.
      Done when: every existing test passes unchanged and `state` reads
      COMPLETED after `run` returns.
- [ ] **`spec-0128-stop`** — `stop(grace)`, dropped and abandoned counts, the
      `Cancelled` doubt.
      Done when: the first six adversarial rows hold, and a stopped run's
      `metEveryGoal` is false with every goal met.
- [ ] **`spec-0128-deadline`** — `stoppingAfter`, and the loop constructs
      checked against it.
      Done when: a scenario whose body never returns ends at the deadline with
      its other users' samples intact.
- [ ] **`spec-0128-hook`** — the shutdown hook and the thread-count assertion.
      Done when: a run killed mid-flight freezes a `Cancelled` result, and no
      proofload-named thread survives any run in the suite.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Does `stop()` block for the grace, or return a handle?** Recommend
   blocking and returning the result: the caller asked to stop, and a
   half-stopped run is a third state nobody wants to reason about.
2. **Is a shutdown hook acceptable in a library?** It is global state, which
   this repository avoids. Recommend installing it only for a run started
   through `Proofload` (the test/CLI entry point) and never from `Engine.run`.
3. **Should `stoppingAfter` have a default of, say, `over * 2`?** Recommend no,
   and expect this to be argued: a default here silently converts a hung run
   into a short one. A run with no bound that hangs is at least honest.
4. **What does a cancelled run's `steady` segment mean?** Recommend refusing it:
   a segment found inside a truncated run is a window nobody measured.
