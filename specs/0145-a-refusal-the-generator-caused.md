# 0145 — A refusal the generator caused

## Problem

Petshop's `heavy.sh` (G1, quiet 16-core machine, `inFlightAtMost(1024)`) holds
50,000/s and the shop answers 49,000/s, 0 failed, p99 6 ms, with 10–15 requests
really in flight. The run is still `Void`: 1.9% of arrivals were refused. At
75k/s it is 4.3%, at 100k/s 6.0%, and the generator's own pauses grow with the
rate (hiccups p99 28, 49, 65 ms).

A 28 ms pause at 50k/s leaves ~1,400 arrivals due at once. The scheduler fires
them back to back on waking, `started` passes the cap within the burst, and the
rest are refused at `VirtualThreads.kt:188` exactly as if the target had filled
the cap. `generatorSaturated()` (`Limits.kt:76`) then voids the run on
`refused > 0`, and `Remedy` and `whyVoid` tell the reader to lower the rate —
blaming the target for this process's stall.

## Not doing

- **Not reducing the pauses.** Allocation per request is a separate spec, after
  a profile says what dominates.
- **No change to an uncapped run**, to the cap check itself, or to deferral:
  an arrival is still started at its due instant or not at all (0144).
- **No change to `lostGround()`** or `fellBehind()`.

## Shape

```kotlin
result.refused               // every arrival not sent, as today
result.refusedAfterStall     // of those, the ones the cap would have had room for
                             // had the generator kept its schedule
result.generatorSaturated()  // (refused - refusedAfterStall) > 0 || ranOutOfRoom() || lostGround()
```

- `Departures` keeps a second counter beside `started`: started users that
  departed **late**, more than `STALL` (1 ms, the hiccup watch's tick) after
  their offset. The lateness is already computed at the due instant; the user
  carries one boolean so `finished()` decrements the right counter.
- A refusal is **after a stall** when `started - lateStarted < cap`: punctual
  users alone would not have filled the cap. Otherwise it is saturation, as now.
- `RunResult.refusedAfterStallPerSecond` in timeline slots, beside
  `refusedPerSecond`; `SteadyState`, `Shards` and `Runs` carry it the same way.
- Reports print "not sent: generator stalled, N" beside "not sent: generator
  saturated, N", each only when non-zero. A run with only the first is not
  void; its summary says the load that left was N short of the profile and why.

## Why this shape

The rule asks a counterfactual the engine can answer at the instant it refuses,
with one counter and no clock correlation: would a punctual generator have
found room? In petshop's runs punctual in-flight is tens against a cap of 1,024,
so every refusal attributes to the stall; a target that genuinely backs up fills
the cap with punctual users and still voids. Two alternatives: **(B)** judge each
refusal by its own lateness only — simpler, but the on-time arrivals refused
while the burst drains still count as saturation, so these runs stay void;
**(C)** mark refusals inside a window after each measured hiccup — needs
timestamped hiccups and an invented drain window. Recommend A.

## Stack

- [ ] **`spec-0145-attribution`** — late-started counter, the rule at the
      refusal, `refusedAfterStallPerSecond`, `generatorSaturated()` change.
      Done when: a test with a blocking target and cap 10 still reads every
      refusal as saturation; a test whose arrivals are released late in one
      burst (fake clock, no sleep, 0124) reads them as after-stall and the run
      is not void; mixed: punctual users at the cap plus a late burst reads
      saturation.
- [ ] **`spec-0145-reports`** — HTML/markdown line, `Remedy`, `whyVoid`, goldens.
      Done when: a golden with only stall refusals shows the line and no void;
      a run with neither moves no golden.
- [ ] **`spec-0145-docs`** — `docs/measurement.md` "refused after a stall",
      invariant 2 names both counters.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

Then petshop's `heavy.sh` at 50k/s against `publishToMavenLocal`: not void,
refusals reported as after-stall.

## Open questions

1. **Should stall refusals void a run above some share?** At 6% the load that
   left is materially less than the profile. Recommend no void, but print the
   share; a later gate can be argued from data.
2. **`STALL` threshold.** 1 ms (the hiccup tick, 50× the departure jitter seen
   at 50k/s) or the run's own interval (20 µs at 50k/s, risks calling jitter a
   stall)? Recommend 1 ms.
3. **Keep `refused` as the total**, or split into two disjoint counts?
   Recommend total: the 0144 invariant `requested = departed + refused +
   dropped` stays true unchanged.
4. **Search ladder**: should a rung with stall refusals count as `Passed`?
   Recommend yes if goals met, with `Rung.offered` reduced by what was refused.
