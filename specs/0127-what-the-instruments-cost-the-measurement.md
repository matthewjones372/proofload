# 0127 — What the instruments cost the measurement

## Problem

0011 measured whether the scheduler keeps up. 0093 measured what a run retains
and allocates. 0056 measured the socket ceiling, and 0118–0120 are three specs
about what that number cannot say. None of them measures the question that
matters most to a *measurement* tool: **how much of what Proofload reports is
Proofload.**

The design is full of decisions justified by that concern — a histogram rather
than a sample list, a recorder per carrier rather than a shared one, `lazySet`
rather than an ordered store, an allocation moved off the departure path, a
`Snapshot` on a thread of its own — and every one of them is argued in a
comment. `:benchmarks:footprint` reports allocation per departure; nothing
reports **latency** per departure, and nothing compares an instrumented run
against an uninstrumented one.

Without that comparison the tool's central claim has a gap in the middle. A p99
of 4 ms against a trivial target could be 4 ms of target and 0 of tool, or 2 and
2, and the repository cannot say which.

## Not doing

- **No JMH.** 0011's reason stands: this weighs a whole run, not a method.
- **No optimisation.** This spec finds the numbers. Whether any of them is worth
  changing is a spec after it, if one is worth writing.
- No comparison against another tool.
- No new production configuration. The "instrumentation off" arm below is a
  test-source engine, not a supported mode — a shipped switch for turning
  measurement off in a measurement tool is a footgun with no use case.
- Not in the test lane and not in coverage, like every other benchmark.

## The comparison

Two targets, both deliberately trivial, so what is left is the tool:

- **A null target** — an action that returns immediately, touching no socket.
  Isolates scheduling, walking, recording and freezing.
- **A loopback target** — `LoopbackTarget` already exists for 0056. Isolates the
  same, plus the transport, against a service time that is real but tiny.

Three arms per target, at each of several rates:

1. **Bare** — the schedule driven, the bodies run, **nothing recorded**: the
   `unrecorded` sink the warm-up already uses. The floor.
2. **Recorded** — the ordinary path. Bare plus histograms, per-second tables,
   lateness, `reached`/`visits`/`attempts`.
3. **Watched** — recorded, plus the things a real run also starts: `hiccups`,
   `Room`, `Progress` at one tick a second, and a live reporter (0081).

Overhead is the difference between arms, reported per departure, at every
percentile — not as a mean, because the whole argument of this repository is
that a mean hides the shape. The distortion that matters is at the tail: a
recorder that costs 200 ns at p50 and 4 ms at p99.9 because it allocates a
second's table is a tool writing its own tail into the target's.

## Shape

```bash
./gradlew :benchmarks:overhead
```

```
target     rate     arm        p50      p99      p99.9    alloc/dep   Δ vs bare
null      50,000    bare      0.9 µs   3.1 µs    41 µs        0 B          —
null      50,000    recorded  1.2 µs   3.8 µs    46 µs      112 B      +0.3 µs
null      50,000    watched   1.2 µs   3.9 µs    52 µs      112 B      +0.3 µs
loopback  10,000    recorded   ...
```

Plus the pieces separately, each an arm of its own so a regression names a
component: the freeze, the merge across recorders, `RunJson`, the HTML report,
and peak heap.

## What is acceptable

Stated as a judgement, in one place, so it can be argued with — the way 0097
states `MATERIAL`:

- **Per-departure overhead must be immaterial against the smallest latency the
  tool claims to measure honestly.** 0039's floor is the natural yardstick: if
  the machine cannot resolve better than *R*, and the tool's own p99 cost is
  under `MATERIAL` of *R*, the instrument is inside its own error bar.
- **Reporting overhead must be zero on the timed path**, not small. Arms 2 and 3
  differing at p99.9 is a bug, not a budget: `hiccups`, `Room` and `Progress`
  are all documented as running on threads no departure touches, and this is the
  measurement that proves it.
- **Allocation per departure must not grow with the run.** 0093's claim,
  re-asserted here at three rates.

A number that fails these is a finding to report, not a build failure. **No gate
on `build`**, for 0093's reason: a threshold on a benchmark that shares a
machine with seven test JVMs fails for the machine.

## Adversarial cases

- **A traced run.** `traced()` writes an exemplar per bucket and a `traceparent`
  header per request. It is the most expensive thing on the timed path and is
  reported as free.
- **A step whose body records many samples** (0075) — the sink path, not the
  step path.
- **Twenty distinct failure reasons**, so the `LinkedHashMap` is hit per request
  and the cap is exercised.
- **A run long enough that `Seconds` has grown thousands of entries** — the
  `while (counted.size <= second) counted.add(...)` grow loop runs on the timed
  path once per second per step, and nothing has measured that spike.
- **A mix of ten arms**, so every recorder holds ten step names.
- **The first request of a run**, which pays every lazy allocation at once.

## Stack

- [ ] **`spec-0127-arms`** — the `overhead` task, the three arms, the null
      target, one rate.
      Done when: the table prints bare, recorded and watched with a per-departure
      delta at four percentiles, and the bare arm records nothing.
- [ ] **`spec-0127-rates`** — the sweep across rates and the loopback target.
      Done when: the delta is reported at three rates on both targets and the
      allocation figure agrees with `:benchmarks:footprint`.
- [ ] **`spec-0127-pieces`** — freeze, merge, JSON and HTML timed separately,
      and peak heap.
      Done when: a report generated from a 60-second run at 10,000/s has a
      figure, and none of it is on the timed path.
- [ ] **`spec-0127-adversarial`** — the six cases above as extra rows.
      Done when: the traced row has a number, and `docs/what-it-costs.md` carries
      it beside the untraced one.

## Acceptance

```bash
./gradlew :benchmarks:overhead
./gradlew build
```

## Open questions

1. **How is arm 1 measured at all, if it records nothing?** Recommend timing the
   bodies from the harness's own side with a single `AtomicLong` per user and
   folding after the run — outside the tool, which is the point.
2. **Is the floor the right yardstick?** Recommend yes and expect argument: it
   is the only figure in the repository that already says what this machine can
   resolve, and inventing a second one would be a threshold with no measurement
   behind it.
3. **Does `watched` include a live reporter's network call?** Recommend a
   collector on loopback: a push to a real endpoint measures the endpoint.
4. **Should `docs/what-it-costs.md` lead with this table?** Recommend yes. It is
   the number a reader deciding whether to trust the tool actually wants, and
   the socket ceiling is not.
