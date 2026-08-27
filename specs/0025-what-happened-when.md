# 0025 — What happened when

## Problem

Every number in the report is a whole-run summary. A run whose target degraded
after ninety seconds and one that was evenly slow throughout produce the same
p99, the same failure count and the same chart — and they call for completely
different work.

This is the biggest thing a reader can see in Gatling's report and cannot see
in this one: requests over time, latency over time, errors over time. Spec 0017
deliberately drew only what was already measured, and the recorder keeps no
timeline at all.

## Not doing

- No per-request event log. Six hundred thousand rows is a memory profile.
- No live streaming during a run. A file at the end is the shape of this tool.
- No configurable resolution. One second is the resolution; a run shorter than
  a few seconds has nothing to plot.

## Shape

```kotlin
result.timeline                       // one entry per second of the run
result.timeline.first().count         // requests that second
result.timeline.first().p99           // latency that second
```

and on the page: requests per second, p50 and p99 over time, and failures over
time, drawn as inline SVG beside the distributions.

- `Second` — a count, ok, failed, and a small histogram's percentiles.
- `RunResult.timeline` — those, in order, from the run's start.
- The recorder keeps a **coarse** histogram per second per step: fewer buckets
  than the full one, because a per-second p99 does not need 0.78% precision and
  a full histogram per second per step is tens of megabytes.

## Why this shape

The memory argument decides the design. A `Histogram` is about 43 KB; ten
minutes of a three-step scenario at full precision is over seventy megabytes of
counters, which is a load generator competing with its own target for memory. A
coarse histogram — thirty-two sub-buckets rather than two hundred and fifty-six,
about 5 KB — costs under ten megabytes for the same run and is good to 6.25%,
which is ample for a line on a chart.

The draft said sixteen sub-buckets and 6% and could not have both: precision
here is one over half the sub-bucket count, and this histogram reports bucket
tops rather than midpoints, so sixteen is 12.5%. The 6% was the promise made to
whoever reads the page, and the byte count was only ever standing in for "not
seventy-seven megabytes", so the sub-bucket count is what moved.

The page states that precision beside the timeline, because a number rendered
to three figures out of a 6.25% bucket is the same lie as an interpolated
percentile.

## Stack

- [ ] **`spec-0025-coarse`** — a histogram with a chosen precision, so the
      timeline can be cheap and the summary can stay exact.
      Done when: the coarse one reports within its stated precision and the
      existing one is unchanged.
- [ ] **`spec-0025-timeline`** — per-second recording in `RunRecorder`, merged
      across shards, and `RunResult.timeline`.
      Done when: a four-second run reports four seconds, counts sum to the
      run's total, and an empty second is present rather than missing.
- [ ] **`spec-0025-charts`** — throughput, latency and failures over time.
      Done when: a run that degraded halfway through looks different on the
      page from one that did not.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

Answered by the architect:

1. **A second with no traffic is recorded as zero**, not skipped. A gap in a
    line chart is information, and a missing point is a lie about the shape.
2. **Seconds are counted from the run's start**, not from wall-clock second
    boundaries, so the first bucket is a full second of load.
3. **The timeline is not in the baseline file.** Comparing two runs
    second-by-second is a different feature, and the format should not carry
    what nothing reads.
