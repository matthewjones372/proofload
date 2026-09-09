# 0017 — Graphs drawn from buckets

## Problem

The report is a table of five numbers per step, and five numbers cannot show a
shape. A p50 of 12ms next to a p99 of 287ms could be a smooth spread or two
separate populations — a fast path and a slow one — and those call for
different work. Nobody can see which from a table.

Spec 0006 deferred charts on the grounds that a table which is correct beats a
chart which is approximate. That was right about approximate charts and wrong
about the data: the histogram already holds the whole distribution, bucket by
bucket, and throws it away when it freezes into five percentiles.

## Not doing

- No charting library, no CDN, no canvas. Inline SVG, hand-written, in the same
  self-contained file.
- No interpolation and no smoothing. A bar is a bucket that was counted, or it
  is not drawn.
- No throughput over time yet. The recorder keeps no timeline, and adding one
  is its own change with its own memory argument — this spec draws only what is
  already measured.
- No comparison against a previous run.

## Shape

Two charts per step, and one for the run:

- **Distribution.** One bar per non-empty bucket, log-spaced along the x axis
  because latency is, with p50 and p99 marked. Two humps look like two humps.
- **Percentile curve.** p0 to p99.9 along a log-scaled x axis, which is where a
  tail becomes obvious rather than a single p99 number.
- **Behind schedule**, drawn the same way, so the reader can see whether the
  backlog was a few late departures or the whole run.

```kotlin
val distribution: List<Bucket> = result["pay"].serviceTime.distribution
// Bucket(upperBound = 128.milliseconds, count = 431)
```

- `Bucket` — an upper bound and a count, in `proofload-core`.
- `Timing.distribution` — the non-empty buckets, so a report draws what was
  counted and nothing else.
- The charts are `<svg>` elements built from those counts in
  `proofload-report-html`, themed with the same CSS variables as the tables.

## Why this shape

The histogram is the measurement; percentiles are a summary of it. Handing the
buckets to the report rather than five numbers means a chart is a drawing of
what was recorded, not a curve fitted to a summary of it — which is the whole
difference between a graph that is evidence and a graph that is decoration.

Only non-empty buckets travel, so the payload is tens of entries per step
rather than the four thousand slots the histogram allocates.

An axis that is logarithmic is not a stylistic choice here. Latency spans four
decades in a normal run, and a linear axis renders every fast request as one
pixel at the origin.

## Stack

- [ ] **`spec-0017-buckets`** — `Bucket`, `Timing.distribution`, and the JSON
      that carries it.
      Done when: a histogram given a known spread reports buckets whose counts
      sum to its count, and the golden JSON holds them.
- [ ] **`spec-0017-charts`** — the distribution and percentile SVGs, marked
      with p50 and p99.
      Done when: the page draws one bar per non-empty bucket, the golden holds
      the SVG, and no chart appears for a step that recorded nothing.
- [ ] **`spec-0017-behind`** — the same chart for scheduling delay, beside the
      banner.
      Done when: a run that kept its schedule draws no chart, and one that did
      not shows where the lateness sat.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

Answered by the architect:

1. **Bars are counts, not percentages.** A percentage hides how much was
    measured, and a bucket with three samples in it should look like three.
2. **The x axis is labelled at decade boundaries** — 1ms, 10ms, 100ms — rather
    than at bucket edges, which are powers of two and mean nothing to a reader.
3. **A chart is drawn from `distribution` alone.** If a number on the page
    cannot be derived from the buckets, it does not go on the chart.
