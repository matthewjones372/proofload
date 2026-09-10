# 0126 — When the carrier is the bottleneck

## Problem

This is the way the current architecture produces a plausible, wrong number,
and every gate stays green while it happens.

A user's lateness is read inside its virtual thread, so a user that cannot get a
carrier *to start* is measured as late — that part is right, and it is why the
tool catches generator saturation at departure. But once a user has departed,
`serviceTime` is wall-clock across `action.attempt`. If the virtual thread is
unmounted mid-step — the socket answered, but no carrier is free to resume the
continuation — that wait is counted as the target's service time.

Now check every existing gate against it:

| Gate | What it sees |
|---|---|
| `behind` | flat. That user departed on time. |
| `lostGround`, `fellBehind` | false, for the same reason |
| `hiccups` | nothing. The tick runs on a dedicated **platform** thread, so a saturated `ForkJoinPool` is invisible to it |
| Little's law | agrees. Observed in-flight is high; predicted from the inflated service time is equally high; the ratio stays ~1 |
| `limits.cpu` | may be high, and is deliberately never gated on |

So a run whose generator is out of carriers reports a slower target, at the
right rate, with a green schedule verdict and a green consistency check. There
is no measurement in the repository that distinguishes it.

The causes are the ordinary ones and none is exotic: a `synchronized` block in a
user's own client (pins the carrier until JDK 24's `-XX:+UnlockExperimentalVMOptions`
removal lands and on any JDK 21 deployment regardless), a blocking JDBC driver
inside a `synchronized` method, a native call, a CPU-heavy step body, and simply
more runnable users than cores.

## Not doing

- **No claim that virtual threads remove blocking or CPU limits.** They remove
  the *thread-count* limit. Pinning, carrier count and cores are unchanged, and
  the documentation currently implies otherwise by omission.
- **No scheduling policy of our own**, no carrier-pool sizing, no work stealing.
  Detect and report; do not manage.
- No JFR dependency in `proofload-core`. If JFR is used it is in the engine or
  a leaf module, per the layering rule.
- No gating a run red on CPU. `docs/what-it-costs.md` already says the generator
  and target share cores on a laptop; that is the ordinary case.

## Three saturations, told apart

The distinction the result has to be able to state:

- **Target saturation** — the target's queue. Service time rises, arrivals are
  punctual, `behind` flat, in-flight rises, law agrees. Today: reported.
- **Load-generator saturation** — this process cannot offer the load. Lateness
  rises, `lostGround`, `offered.left` below `asked`, descriptors or ports tight.
  Today: reported.
- **JVM execution saturation** — the load leaves on time and the *measurement*
  of it is inflated by this process's own scheduling. Today: **reported as
  target saturation.** This spec is about the third.

## Shape

```kotlin
result.execution.pinned          // Timing — how long a carrier was pinned, when
result.execution.carriers        // 8 — what the scheduler had
result.execution.runnable        // peak virtual threads runnable and unmounted
result.execution.starved         // Doubt, where the queue was long enough to move a tail
```

Three candidate sources, all off the timed path:

1. **A carrier-latency probe.** A second `Hiccups`-shaped watch whose tick is
   submitted to the **virtual-thread scheduler** rather than to a platform
   executor, sampling the same 1 ms schedule. The gap between it and the
   existing platform hiccup series *is* the carrier queueing delay, and both
   already exist as a shape. Cheap, needs no JFR, and measures exactly the thing
   that is currently invisible. **Recommended.**
2. **JFR events.** `jdk.VirtualThreadPinned` and `jdk.VirtualThreadSubmitFailed`
   give the pinning cause and a stack, which 1 cannot. Precise, and a dependency
   on a recording being enabled — recommend it as an *opt-in* second layer
   behind 1, in the engine, off by default.
3. **Polling the scheduler's queue depth** through `ForkJoinPool` internals.
   Rejected: it is not public API and it would be a number that breaks on a JDK
   upgrade.

1 is the measurement, 2 is the diagnosis. `starved` fires on 1 by the rule 0097
already sets: carrier delay above `MATERIAL` of the step's own tail, floored by
the machine's resolution where `calibrate()` measured one. It becomes a `Doubt`
(0121), so a starved run is not `Valid`.

## What Proofload is and is not prepared to claim

To be written into `docs/concepts.md` verbatim, because the absence of it is
half the problem:

**Prepared to claim.** That a user which could not start on time is measured as
late, not as slow. That a blocked user costs a thread and not a carrier, for
blocking the JDK has instrumented. That the number of concurrent users is not
bounded by the carrier count.

**Not prepared to claim.** That a pinned carrier is detected without this spec.
That user code holding a monitor, doing native work, or burning CPU is separable
from the target's latency without this spec. That service time excludes this
process's own scheduling delay. That virtual threads make a CPU-heavy step body
free.

## Adversarial cases

| Target | Expected after this lands |
|---|---|
| step body inside `synchronized`, one per user, 1,000 users | `pinned` non-empty, `starved` fires, run not `Valid` |
| CPU burn sized to every core, punctual departures | `starved` fires; today the run reads as a slow target |
| blocking JDBC driver against a 1-connection pool | `queued` carries the pool wait (already), `starved` does **not** fire — the two must not be confused |
| ordinary HTTP run, 8 carriers, 50 in flight | `starved` silent, `pinned` empty |
| `Thread.sleep` in a `pause` step | never `pinned`: it unmounts, and a false positive here would fire on every think-time run |

## Stack

- [ ] **`spec-0126-probe`** — the virtual-thread carrier probe beside `Hiccups`,
      and `Execution` on the result.
      Done when: a run with every carrier pinned reports carrier delay orders of
      magnitude above its platform hiccups, and an idle run reports neither.
- [ ] **`spec-0126-doubt`** — `starved` as a `Doubt`, on 0121's rule.
      Done when: the pinning and CPU rows are not `Valid`, and the JDBC and
      think-time rows are.
- [ ] **`spec-0126-jfr`** — the opt-in JFR layer naming the pinning site.
      **Not recommended.** Detecting starvation is what the invariant needs;
      naming the site is a profiler, and `-Djdk.tracePinnedThreads` and JFR
      already do it outside the tool. The probe above answers "were the
      carriers the bottleneck"; whoever gets a yes can reach for a profiler.
      Left in the stack so the choice is recorded rather than silently dropped.
      Done when: the `synchronized` row names the method, and a run without the
      flag loses nothing but the name.
- [ ] **`spec-0126-claims`** — the two lists above in `docs/concepts.md` and in
      the README's virtual-threads sentence.
      Done when: no page in the repository implies virtual threads remove
      blocking or CPU limits.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :examples:timingTests
```

## Open questions

1. **Is the probe's own tick a carrier it takes from the run?** Yes, one. On a
   saturated pool that is the point; on an idle one it is negligible. Recommend
   measuring the cost in `:benchmarks:ceiling` before shipping it.
2. **Should carrier delay be subtracted from service time?** Recommend **no**.
   Subtracting an estimate from a measurement is exactly the interpolation
   AGENTS.md forbids. Report it beside, as `queued` and `behind` already are.
3. **Does this need the carrier count to be knowable?** `jdk.virtualThreadScheduler.parallelism`
   defaults to the core count and is settable. Recommend reading the system
   property and reporting `Absent` where it was not set rather than assuming.
