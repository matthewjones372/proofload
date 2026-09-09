# 0032 — Steady state, found rather than assumed

## Problem

Every sample from the first cold request goes into the same histogram. On the
JVM the first seconds of a run are class loading, C2 compilation and a cold
connection pool, so the p99 on the report is partly a measurement of starting
up. The roadmap already names this: a warm-up phase excluded from the numbers
rather than discarded by hand.

The usual fix is a warm-up setting, and the evidence says that setting is a
guess. Warm-up estimates written by the developers of the benchmarks they were
estimating had a median error of 28 seconds and were accurate in 19% of forks,
and JMH's defaults did worse than the guesses `[WARMUP-JMH]`. Worse, a steady
state is not guaranteed to exist at all: across 3,660 process executions of
small deterministic benchmarks, at most 43.5% of pairs consistently reached one,
and some runs got slower and stayed slower `[WARMUP]`.

So neither a default nor a knob is honest. The run has a timeline as of 0025;
the segment can be found in it, reported, and where there is no segment, said.

## Not doing

- No changepoint library. Core takes no dependencies, and the detection here is
  a few dozen lines over a timeline that already exists.
- No per-step steady state. A run settles or it does not; a step that settles
  inside a run that did not is a curiosity, not a verdict.
- No automatic re-running, no automatic extension of the run. Both hide the
  finding, which is the thing worth having.
- No discarding by default. What was discarded is reported, and the whole-run
  numbers stay where they are.

## Shape

```kotlin
result.steadyState        // From(offset = 24.seconds) or NeverSettled(why)
result.steady             // the same result over the steady segment only
result.steady[pay].serviceTime.p99
```

and on the page, above the numbers:

> Settled after **24 s**, judged over the remaining 96 s. The first 24 s are
> drawn on the timeline and excluded from every percentile below.

or

> **Never settled.** The last 30 s were still 14% slower than the 30 s before
> them. Percentiles below describe the whole run, and describe a target that
> was still moving.

- `SteadyState` — `From(offset)` or `NeverSettled(why)`, on `RunResult`.
- `RunResult.steady` — the same value, restricted. Goals judge this one when it
  exists.

## Why this shape

Three ways to find the segment. A coefficient-of-variation rule over a trailing
window is the simplest and is the one the literature criticises most directly,
with a case where it demanded 247 iterations against the 5 actually needed
`[COV-CRITIQUE]`. A changepoint test on the interval means is what the warm-up
papers use and costs the most code. A monotone-improvement test sits between
them: find the earliest offset after which no later window is materially better
than the last one, and require the tail to hold within tolerance of its own
mean.

Recommend the third. It is a dozen lines over `timeline`, it fails safe by
reporting `NeverSettled`, and it does not need a library. If it proves weak, the
changepoint version replaces it behind the same value.

`NeverSettled` deliberately does not fail the run. A tool that fails a build on
a detector's opinion is a tool whose detector gets turned off; a tool that says
so at the top of the page gets read.

## Stack

- [x] **`spec-0032-detect`** ([#30](https://github.com/matthewjones372/proofload/pull/30)) — `SteadyState` and the detector over `timeline`.
      Done when: a synthetic timeline that improves and then flattens reports
      the flat point, one that never flattens reports `NeverSettled`, and one
      that degrades reports `NeverSettled` with that as the reason.
- [x] **`spec-0032-judge`** ([#33](https://github.com/matthewjones372/proofload/pull/33)) — `RunResult.steady`, goals judged over it, and the
      report saying what was excluded.
      Done when: a run with a slow first 20 s reports a p99 that does not
      include them, and the page names the discarded window.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **What tolerance counts as settled?** Recommend 5% relative between the last
    window and the one before, stated in one place and printed on the page. It
    is a default, not a discovery, and the page should say so.
2. **How short is too short to judge?** Recommend refusing a verdict under ten
    intervals: a four-second run has nothing to detect, and `SteadyState` should
    say that rather than guess.
3. **Do goals judge the steady segment or the whole run?** Recommend the steady
    segment when there is one, because that is the question a goal is asking,
    with the page saying which was used. On `NeverSettled` they judge everything
    and the verdict carries the warning.
4. **Does this depend on 0025 landing first?** Yes. Recommend saying so here
    rather than building a second timeline.
