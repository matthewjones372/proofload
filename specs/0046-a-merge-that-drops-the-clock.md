# 0046 — A merge that drops the clock

## Problem

`Runs.merged` builds a `RunResult` field by field, and `timeline` is not one of
the fields it builds. Neither is `StepStats.timeline` in the step merge. Both
default to empty, so a merged run reports that nothing happened in any second
of it, silently and without a warning anywhere.

The omission is visible next to its neighbour. `arrivals` is also empty on a
merge, and it carries three lines saying why — a gap between one run's last
departure and the next run's first is one nobody scheduled. `timeline` has no
such comment because nobody decided it; it was added by 0025 after 0037's merge
was designed, and a data class with defaulted fields does not fail to compile
when a new one appears.

It matters more than a missing chart. 0032's steady-state detector reads
`timeline`, so `runs.merged.steady` cannot find a segment in ten runs and will
report that it never settled — a detector answering confidently from no data,
in the one place a reader has most reason to trust it. The same is true of
anything else built over the timeline later.

## Not doing

- No merged timeline in the baseline file. 0025 settled that the format does
  not carry one, and 0037 reads its runs from that format.
- No re-deriving a timeline from the merged histograms. Per-second counts are
  not recoverable from a run's totals.
- No change to the `arrivals` decision, which is argued and correct.

## Shape

Two honest answers, and the spec has to pick one:

```kotlin
runs.merged.timeline        // and what should this be?
```

**Concatenate.** Ten runs of two minutes give a two-hundred-and-forty-second
timeline, one run after another. Every second is real and the counts sum, but
the shape is ten warm-ups in a row, and second 121 means "the first second of
run two", which no reader will assume.

**Superimpose.** Second *n* of the merge is second *n* of every run, counts
summed and coarse histograms merged. Ten runs of two minutes give a
two-minute timeline of ten times the load, which is what a reader of a merged
result expects and what a warm-up detector wants: the cold start of ten
processes lands in the same early seconds rather than being smeared across the
whole thing.

**Refuse.** `merged.timeline` is empty and something says so, the way
`arrivals` does.

Recommend **superimpose**, padding the shorter runs with zero seconds up to the
longest, and refusing only where some runs carry a timeline and others carry
none at all.

## Why this shape

Superimposing is the only option that keeps the merge answering the same
question its percentiles answer. `merged[pay].serviceTime.p99` is already the
percentile of ten runs' samples pooled, not ten runs laid end to end; a
timeline that concatenated would describe a different experiment from the
numbers beside it on the same page.

It also matches what replication is for. The reason to run ten processes is
that JIT profile and heap layout differ between them, and the thing you want to
see is whether they all settle at the same point. Superimposed, that is a shape
on a chart. Concatenated, it is ten shapes a reader has to align by eye.

The cost is that a second's percentile in the merge is over ten runs' samples
in that second, so a run that was slow only in its own third second is diluted.
That is the same trade the merged percentiles already make, and `each` still
holds every run separately for anyone who needs it.

## Stack

- [ ] **`spec-0046-superimposed`** — `timeline` in `Runs.merged` and in the step
      merge, second *n* against second *n*, padding to the longest run.
      Done when: ten runs of the same length merge to a timeline of that length
      whose counts sum to the merged result's count, a shorter run is padded
      with zero seconds rather than refused, a run with no timeline beside one
      with a timeline is refused naming the difference, and `runs.merged.steady`
      finds the segment a single run of the same shape finds.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Is refusing unequal lengths too strict?** Yes — it refuses the normal case.
    Timeline length is the last second anything was recorded in, so two honest
    replications of one two-minute plan routinely freeze at 119 and 120 seconds.
    Pad to the longest instead. A zero second is a measurement, not an
    invention: 0025 already writes interior quiet seconds as zeros, and the only
    reason trailing silence goes unpadded in a single run is that a single run
    has no defined end — a merge has one. And `Runs` already refuses unlike
    plans, so every run in one has the same *intended* length by construction;
    what differs is jitter in where the last response landed, which the plan
    check has already cleared.

    Refuse only where some runs carry a timeline and others carry none. Padding
    an absent timeline *would* be an invention: the file format does not carry
    one, so not knowing what happened in a second is a different fact from
    knowing nothing happened.
2. **Should the merged page draw it?** Recommend yes, labelled as the number of
    runs behind it, since 0037's open question already asks the page to say how
    many runs it drew.
3. **Does this want a test that a new field cannot be forgotten again?** A merge
    that enumerates fields will rot the same way the next time one is added.
    Recommend a test that constructs a `RunResult` with every field non-default
    and asserts the merge of one run equals it — cheap, and it fails the day
    somebody adds a field without deciding what merging means for it.
