# 0047 — A precision that travels with the number

## Problem

`Histogram` knows its own precision and refuses to merge with a histogram of a
different one. `Timing` — the frozen value everything downstream actually reads
— does not know it at all.

The guarantee therefore stops at the freeze. `Runs` merges `List<Timing>` by
grouping buckets on `upperBound` and summing, so merging a coarse timing with a
full-precision one would produce a distribution that is half one bucket scheme
and half another, silently, and read percentiles off it. Nothing does that
today because seconds only ever merge with seconds, but the only thing stopping
it is that nobody has written the line.

Two agents found this independently, from opposite ends. One building the
coarse histogram noted that a `Second`'s error bar is knowable only by reaching
for `Histogram.COARSE_PRECISION` by hand. One building the merge noted that
`List<Timing>.merged()` has no check where `Histogram.merge` has one.

The report already has to state precision where it prints — `AGENTS.md` is
explicit that a number rendered to three figures out of a 6.25% bucket is the
same lie as an interpolated percentile — and today it does that by knowing,
statically, which numbers came from which histogram. That is a fact about the
code holding a fact about the data, and it is one refactor away from being
wrong.

## Not doing

- No change to either precision, or to how percentiles are computed.
- No per-bucket error bars. One figure per timing is what a reader can use.
- No new precisions. Two is what exists; this is about carrying them, not
  adding a third.
- No change to `Interval`, which answers a different question — how many
  samples a percentile rests on, rather than how wide the bucket was.

## Shape

```kotlin
result[pay].serviceTime.precision      // 0.0078125
result.timeline.first().serviceTime.precision   // 0.0625
```

- `Timing` carries the precision of the histogram it was frozen from.
- `List<Timing>.merged()` refuses across unlike precisions, the way
  `Histogram.merge` already does, naming both.
- The report reads the figure off the value it is printing rather than off a
  constant it was told to use.

## Why this shape

The alternative is a second type — a `CoarseTiming` distinct from `Timing` —
which makes the mismatch a compile error rather than a runtime refusal. That is
stronger and it doubles every signature that takes a timing, including the goal
vocabulary, the report and the baseline format. For two precisions that differ
by one field, a field is the proportionate answer.

Carrying it also removes the last place where the page's honesty is
load-bearing on a human remembering something. A precision beside the number is
the same argument as a sampling interval beside a percentile, and this
repository already made that argument once.

## Stack

- [x] **`spec-0047-carried`** — `Timing.precision`, set at freeze, refused
      across unlike values in the merge.
      Done when: a timing frozen from a coarse histogram reports the coarse
      figure, merging unlike precisions fails naming both, and the report prints
      the figure it read rather than one it was given.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **What is the precision of a timing built from samples in a test?** It has a
    histogram behind it, so it has one. Recommend no default on the field, so a
    fixture cannot quietly claim full precision it never had.
2. **Does the baseline format carry it?** Recommend yes. A baseline read back
    and compared against a run of a different precision is exactly the silent
    pooling this spec exists to stop, and the format is already at version 3.
    **Not built here.** Every timing the format has ever carried was counted at
    full precision — the step timings, and since version 6 the run's lateness
    and the injector's own stalls, all off a plain `Histogram`; the coarse
    tables are the timeline and the per-second lateness, and neither is
    written. So the reader states full precision as a fact about those files
    rather than a guess about them, and a version that ever writes a coarse
    table has to carry the figure per timing. Recommend its own entry then.
3. **Does `Second` still need `COARSE_PRECISION` in public view?** Recommend
    keeping the constant but letting the page read `precision` off the value.
    The constant is how the recorder chooses; the field is how a reader checks.
4. **What is an empty table's precision?** Settled when built: the width is a
    property of the counters rather than of what landed in them, so an empty
    `Histogram.coarse()` still freezes to a timing claiming 6.25% and a merge
    of empty seconds keeps it. `Timing.none` — which stands for no histogram at
    all rather than an empty one — carries null. `Runs.merged`'s guard test
    caught the difference.
5. **What was the page claiming before?** Three goldens printed a
    `timelinePrecision` of 6.25% for runs whose timeline was empty. Nothing was
    counted at that width because nothing was counted at all, which is exactly
    "a fact about the code holding a fact about the data". They now print
    nothing, and that is the whole of this change's golden diff.
