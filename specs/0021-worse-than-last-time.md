# 0021 — Worse than last time?

## Problem

A team runs Kestrel on every release and has no way to ask the only question
they actually care about: is this worse than last time? Every run is reported
in isolation, so comparison happens by eye, across two browser tabs.

Doing it by eye is worse than not doing it, because a p99 from a four-second
run moves by tens of milliseconds between identical runs. A team that starts
comparing p99s will chase changes that are noise, and will learn to ignore the
report — which is how a load test dies.

The fix is not a bigger number. It is admitting how much a percentile is
allowed to move: with 480 samples the p99 sits on five of them, and its
sampling interval is wide. Two runs whose intervals overlap have not been shown
to differ, and the report should say exactly that.

## Not doing

- No storage service, no database, no server. A baseline is a file.
- No history beyond one baseline. Trends across twenty runs are a different
  feature with a different shape.
- No automatic failing. A comparison is reported and can be asserted on; it
  does not fail a build by itself.
- No JSON parser. The baseline format is written and read by this module, and a
  format that needs a parser is a dependency in disguise.

## Shape

```kotlin
import io.github.matthewjones372.kestrel.baseline.readBaseline
import io.github.matthewjones372.kestrel.baseline.writeBaseline

val result = simulation.run()
val comparison = result.against(readBaseline(Path.of("baseline.kestrel")))

result.writeBaseline(Path.of("baseline.kestrel"))
```

and on the page:

> `/pay` p99 **302 ms** (250–380 ms, 5 samples). Last run 290 ms —
> **not distinguishable**.
>
> `/cart` p99 **890 ms** (810–960 ms). Last run 290 ms — **worse**, and the
> intervals do not overlap.

- `Timing.interval(percentile)` — the sampling interval for a percentile,
  computed from the buckets and the count.
- `Change` — a step, a percentile, then and now, and a verdict: better, worse,
  indistinguishable, new, or gone.
- `RunResult.against(baseline)` — every step compared.
- `kestrel-baseline` — a module that writes and reads a run in a line-oriented
  format of its own.

## Why this shape

The interval is the whole point. The rank of a percentile in a sample is
binomially distributed, so the interval follows from the count and the buckets
without assuming anything about the shape of the latency distribution — no
normality, no smoothing, no model. It is arithmetic over what was measured,
which is the standard everything else here is held to.

Reporting "not distinguishable" rather than a percentage change is what stops a
team acting on noise. It is also honest about the cheapest fix available to
them: run for longer, and the interval narrows.

A file rather than a service because a load test that needs infrastructure to
compare two runs will not be run. A workflow already keeps artifacts between
runs, and that is enough.

## Stack

- [ ] **`spec-0021-interval`** — `Timing.interval(percentile)`, from the
      buckets and the count.
      Done when: a thousand samples give a narrower interval than fifty of the
      same distribution, and an empty timing has none.
- [ ] **`spec-0021-comparison`** — `Change`, its verdict rule, and
      `RunResult.against`.
      Done when: two runs of the same distribution are indistinguishable, a run
      three times slower is worse, and a step present in only one is named.
- [ ] **`spec-0021-baseline`** — the `kestrel-baseline` module: write a run,
      read it back, compare.
      Done when: a run written and read back compares as indistinguishable
      from itself, and an unreadable file is an error a caller can handle.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

Answered by the architect:

1. **95% intervals**, and the report says so. Anything tighter invites acting
    on noise; anything looser never distinguishes anything.
2. **A step in one run and not the other is named, not silently dropped.** A
    step that stopped running is the most interesting change there is.
3. **The baseline holds the buckets, not the percentiles.** An interval cannot
    be recomputed from five numbers, and a baseline that cannot be compared
    honestly is not worth keeping.
