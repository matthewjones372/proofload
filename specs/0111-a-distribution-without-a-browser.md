# 0111 — a distribution without a browser

## Problem

The README's claim for the page is "real percentiles: bars are counted buckets, a
percentile is the top of the bucket a sample landed in, on a log axis. No
smoothing, no interpolation." The markdown a job summary carries has none of
that. `Markdown.kt` imports `Histogram` for its precision constant alone, so a
step arrives as `p50 41ms  p99 190ms` — two numbers off a shape nobody sees.

Two numbers hide the thing the shape is for. A bimodal step and a tight one with
the same p99 read identically, and the bimodal one is a cache that misses a
tenth of the time. Anyone reading a job summary, a PR body or a chat has to open
the page to find that out, which is the point at which they stop bothering.

## Not doing

- **Not a chart.** No SVG, no image, no colour. Text a log can carry.
- **Not new measurement.** The buckets are counted already; this renders them.
- **Not interpolating.** A bucket with no samples is a blank row, not a smoothed
  one — the README's claim is the constraint.
- **Not the timeline.** Second-by-second lateness is its own shape and its own
  spec if anyone wants it.
- **Not MCP-specific.** It lands in `Markdown.kt`, so every reader gains it.

## Shape

Under each step in the table, the shape the percentiles were read off:

```
place order   p50 41ms   p99 190ms
    1ms  ▏      12
   10ms  ▇▇▇▇▇  1841
  100ms  ▇▇▇▇▇▇▇▇▇▇▇▇  4903
     1s  ▇      244
```

One row per occupied decade, the bar proportional to the count, the count
printed beside it so the bar never has to be trusted.

```kotlin
// Markdown.kt, beside the other private renderers
private fun StepStats.distribution(width: Int = 12): String
```

## Why this shape

Decades rather than every bucket: a run holds far more buckets than a summary can
carry, and the log axis the page draws is already decade-shaped, so collapsing to
decades is the same view at lower resolution rather than a different one. The
alternative is every occupied bucket, which is faithful and unreadable for a run
with a long tail; recommended against.

The count sits beside every bar because a bar scaled to the largest bucket makes
a 12-sample row and a 12,000-sample row look alike at small widths, and the
number is what stops that being misleading.

## Stack

- [ ] **`spec-0111-decades`** — `StepStats.distribution()` and its tests, not yet
      wired into the table.
      Done when: the rows' counts sum to the step's request count, an empty
      decade inside the range renders blank, and a single-sample step renders one
      row.
- [ ] **`spec-0111-in-the-table`** — the step table carries it, goldens updated.
      Done when: every golden in `proofload-report-github` shows the shape, and
      the widest run's summary still fits a job log line width.

## Acceptance

```bash
./gradlew :proofload-report-github:test
./gradlew build
```

## Open questions

1. **`▇` or `#`?** `markdown()` promises no colour and no emoji; a block element
   is neither, but it is not ASCII, and a plain job log may render it as tofu.
   Recommended: `#`, because the one reader who cannot render it is the one who
   cannot choose a font.
2. **Every step, or only the ones that missed a goal?** A twenty-step plan
   becomes eighty rows. Recommended: only steps with a goal that missed, plus the
   slowest step where nothing missed — the shape is diagnostic, and a passing run
   does not need eighty rows.
3. **Does the HTML page change?** No. But if the decades disagree with the
   page's axis, one of them is wrong. Recommended: a test asserting the decade
   bounds match `Histogram`'s.
4. **Is this worth it before 0110 lands?** The value in CI is real on its own,
   but the reason it came up was the chat. Recommended: build 0110 first.
