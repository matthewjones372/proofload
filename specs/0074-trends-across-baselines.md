# 0074 — Trends across baselines

## Problem

Comparison here is pairwise: 0021 puts a run against one baseline, 0038 one
population against another, answering better, worse or cannot tell. Neither says
what a statistic has been doing, the regression that pair is blind to by
construction: a p99 creeping 2% a week sits inside 0038's interval every week —
a runner's own coefficient of variation is about 2.66% `[CINOISE]` — so
`Runs.against` says `CannotTell` forty weeks running while p99 moves a third.

The material is on disk: `writeInto` names each run by start time and pid,
`Runs.readAll` reads a directory of them oldest first, and 0062 settled a point
is a population, not a run. `ROADMAP.md` lists this under Known and unwritten.

## Not doing

- No database and no service. A trend is a directory of directories, the way a
  baseline is a file.
- **No fitted line and no slope.** "0.8 ms a week" is a model's output, and
  `AGENTS.md` says a number in a report is a measurement or it is a lie.
- No smoothing, no moving average, no interpolation across a point nobody ran.
  0017's rule holds: drawn from what was counted, or not drawn.
- No trend of single runs. A point is a `Runs`, for 0062's reason.
- No change-point detection as a verdict. e-divisive wants thirty days and
  stable hardware first `[HUNTER]`; MongoDB's thresholds gave 2,393 alerts in
  five months of which 24 were useful `[CHANGEPOINT]`.
- No gate. As in 0021, 0030 and 0038, nothing here fails a build by itself.
- No change to the baseline format, and no second format.

## Shape

```kotlin
// history/<label>/run-<started>-<pid>.kestrel, one subdirectory per commit
val trend = readTrend(Path.of("history"), p99(pay), acceptable = 5.percent)
val point = trend.points.last()
point.label       // "9f2c1ab" — the directory it was read from
point.reading     // 302_000_000.0, off that point's merged population
point.band        // 288_000_000.0 .. 319_000_000.0, its own runs resampled
point.machine     // what measured it, beside point.runs
trend.ends        // Difference: the oldest point against the newest
trend.steps       // the adjacent pairs whose runs support a move
trend.comparisons // 39, so the page can say how many it made
```

and on the page: `pay` p99 over **40 points**, oldest to newest **31% slower**
(24% to 38%); two adjacent pairs moved by more than their runs can tell, of 39
comparisons at 95%, so about two are the machine — places to look, not findings;
points 1–12 ran on 4 cores and 13–40 on 8, and the line breaks there.

- `Trend` and `Trend.Point` in core: one `Statistic`, an ordered list of
  labelled `Runs`, and the adjacent `Difference`s. `readTrend` in
  `kestrel-baseline` reads each subdirectory with `readAll`, and the page is a
  third kind beside the run report and `CapacityPage`, on 0044's stylesheet.
- `Band(low, high)` — a point's reading resampled, in that statistic's units.
  Not `Spread`, the range a *ratio* sits in, nor `Interval`, a sampling interval
  inside one run.

## Why this shape

`Resampler` in `Difference.kt` already resamples one `List<Samples>` onto a
single ladder of bucket bounds and `bootstrap` divides two such resamples, so a
band is that class on one side with no division, same seed, same ten thousand.
But a ratio interval cannot be recovered from two bands, and non-overlap is a
stricter test than 0038's, so bands are drawn and every step is computed by
`Runs.against` on that pair. The headline is oldest against newest, the one
comparison a creep shows in; the middle says whether it was a step or a drift.

The inflation is real — 39 pairs at 95% expects about two spurious steps in an
unchanged series. Widening every interval by the comparison count would make 95%
here mean something other than 95% on the run report; naming no step throws away
what a series can point at. Recommend the third: the same 95% as everywhere, the
count and its consequence beside the list, which anyone can check.

A machine change is a break, never a smoothed segment: `Runs` already refuses to
merge unlike machines, and a page per machine would hide that the series changed
runners at all. Subdirectories rather than one flat directory because the file
has no label to key on: `Baseline.kt` writes started-at, machine, probe, plan
and buckets, and nothing that names what was built.

## Stack

- [x] **`spec-0074-band`** — `Band`, from the existing resampler.
      Done when: a band contains its point's merged reading, runs that landed
      further apart give a wider one, and a point under five runs has none.
- [x] **`spec-0074-trend`** — `Trend`, `ends`, `steps`, `comparisons`.
      Done when: identical points name no step and `ends` contains 1.0, one
      injected step names that pair alone, and under three points is refused.
- [x] **`spec-0074-history`** — `readTrend`, one subdirectory per point.
      Done when: twenty subdirectories read back as twenty labelled points in
      measurement order, one whose runs disagree on plan or machine is refused
      naming it, and an empty one is named rather than skipped.
- [x] **`spec-0074-page`** — the page and what it may claim.
      Done when: the golden holds a band and a runs-machine-probe row per point,
      no segment or named step crosses a machine change, and the note quotes how
      many comparisons at 95% and how many are expected to be the machine.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Where does a point's label come from?** The file carries none. Recommend
    the subdirectory name, written by whoever put the runs there
    (`history/$GITHUB_SHA`), over a commit field only CI could fill.
2. **What orders the points?** Recommend each point's oldest run — measurement
    order, which `Runs.readAll` already imposes inside a point — and saying so,
    since a rebuilt commit lands where it was measured, not where git puts it.
3. **Does the floor travel per point?** Not today: version 4 carries the probe
    but no floor, and 0039's third question recommended one that was never
    built. Recommend drawing the probe per point — a canary separating a slower
    runner from a slower service `[CANARY]` — and leaving the floor to 0039.
4. **How few points is not a trend?** Recommend refusing under three, as 0038
    refuses under five runs a side. Two points with bands is a pairwise
    comparison wearing a chart, which is already 0038's job.
5. **One statistic per page, or several?** Recommend one: nanoseconds and shares
    of requests are two y axes, and a page per statistic costs only a filename.
