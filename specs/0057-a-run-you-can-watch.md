# 0057 — A run you can watch

## Problem

The only `println` in the library is the one that says where the report went.
A ten-minute soak prints nothing for ten minutes. A capacity search whose
`worstCase` is half an hour of holds prints nothing for half an hour.

Two things follow. People assume it has hung and kill it — and killing it loses
every sample, because nothing is written until the run returns. And a run that
is going wrong in a way the numbers would show at thirty seconds is discovered
at ten minutes, which is nine and a half minutes of a shared environment spent
on a run somebody already knew was void.

## Not doing

- No TUI, no ANSI repainting, no progress bar. A CI log is not a terminal.
- No metrics push, no Prometheus endpoint, no live web page. That is a leaf
  module if anyone wants one.
- No partial report file. A half-written report is a page somebody quotes.
- No new numbers. Progress prints what the run already measures.

## Shape

Core declares the seam; the engine drives it.

```kotlin
fun interface Progress {
    fun tick(elapsed: Duration, snapshot: Snapshot)

    companion object {
        val silent: Progress
        fun lines(every: Duration = 5.seconds): Progress
    }
}
```

```
proofload: 00:30  departed 44,231  in flight 312  behind p99 2.1ms
proofload: 00:35  departed 51,678  in flight 298  behind p99 2.0ms
proofload: rung 400/s  passed   p99 84ms   failed 0.0%
```

- `Proofload(progress = ...)`, defaulting to `lines()`; `@LoadTest` and the
  Kotest module supply `silent`.
- A search prints a line per rung, with its verdict and whether it was void.

## Why this shape

What a tick may read is the whole design, and it is constrained by something
that already exists. `RunRecorder` is deliberately not thread-safe, and
`Recorders` hands each writer a shard *claimed* out of an array — so a progress
thread cannot read a shard without either taking it from a writer or racing an
`ArrayList` that is being grown. Watching must not move what is watched; that
is the rule `hiccups` already follows, and it is the rule that makes this a
spec rather than a `println`.

So the first version reports only what is already outside the recording path:
the scheduler's own departure count, the `Departures` latch's outstanding
count, and the lateness the arrival recorder folds on the one thread that sees
every departure. Those are single-writer values published through volatile
longs, read approximately, and no request pays for them.

Requests, failures and a percentile are the second question, and the cheapest
honest answer is a volatile count per shard, written by whichever thread holds
it — single-writer, so a plain read from the ticker cannot tear. That adds a
volatile store to the recording path. It is probably free and this repository
does not accept "probably": it lands only if `:benchmarks:ceiling` says the
ceiling did not move.

## Stack

- [x] **`spec-0057-progress`** — `Progress`, `silent`, `lines()`, wired into
      the engine, reporting only scheduler-side numbers.
      Done when: a run prints a line every five seconds and one at the end, a
      run given `silent` prints nothing, and the ceiling harness reports the
      same ceiling as before.
- [x] **`spec-0057-quiet-tests`** — `@LoadTest` and the Kotest extension
      supplying `silent`.
      Done when: neither framework's output gains a line, and a `main` still
      prints.
- [x] **`spec-0057-counts`** — per-shard volatile counts, so a tick can report
      requests and failures.
      Done when: the numbers match the frozen result at the end, and
      `:benchmarks:ceiling` shows no change against the row before it.
- [x] **`spec-0057-search`** — a line per rung, with the verdict.
      Done when: a search prints each rung as it finishes, marking void ones.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :benchmarks:ceiling
```

## Open questions

1. **Is a percentile in the line worth a volatile store per request?**
    Recommend splitting it as above and letting the benchmark decide, and
    recommend that if it costs anything measurable the line carries counts
    only. A progress line is a comfort; the ceiling is the product.
2. **stdout or a logger?** Recommend stdout. Core has no logging dependency and
    the layering rule means it is not getting one; a caller who wants a logger
    implements the one-method interface.
3. **On by default?** Recommend yes for `Proofload()` and no under either test
    framework. A load test that prints 120 lines into a JUnit report is noise,
    and a `main` that prints nothing for ten minutes is the problem.
4. **Does a tick's snapshot go in the report?** Recommend not: the timeline
    already carries the run second by second, from data that was frozen rather
    than sampled, and a second source for the same shape is two numbers that
    can disagree.
