# 0003 — What a run measured

## Problem

A scenario can be described and a profile can say when each user departs, but
nothing can say what happened. There is no type for a result, so the engine
spec has nothing to fill and no test can assert on a number.

The trap this spec exists to avoid: a load generator that reports one latency
per request measures the interval it chose to measure. If a request was meant
to depart at 20ms and the generator got to it at 300ms, a tool that times from
"when I sent it" reports the target as fast and its own backlog as nothing.
That is coordinated omission, and it is the default bug in this class of tool.

## Not doing

- No engine, no threads, no clock. Nothing here runs a scenario — spec 0004.
- No rendering. HTML and `$GITHUB_STEP_SUMMARY` are 0005 and 0006. This spec
  produces values; a sink formats them.
- No assertion DSL. A `RunResult` is a value and Kotest matchers already read
  well against one.
- No percentile interpolation, ever. See below.
- No per-request event log. A run at 1000/s for ten minutes is 600,000 requests
  and keeping each one is a memory profile, not a measurement.

## Shape

```kotlin
import io.github.matthewjones372.proofload.RunResult
import kotlin.time.Duration.Companion.milliseconds

val result: RunResult = /* spec 0004 produces one */

result["pay"].count                   // 3000
result["pay"].ok                      // 2959
result["pay"].failures                // {"status 503": 41}
result["pay"].serviceTime.p99         // sent -> response arrived
result["pay"].responseTime.p99        // intended departure -> response arrived
result.behind.max                     // worst gap between intended and actual send

result["pay"].serviceTime.p99 shouldBeLessThan 200.milliseconds
```

- `Histogram` — log-linear buckets, records nanoseconds, keeps a count per
  bucket and nothing else. States its own precision.
- `Timing` — a histogram read as `p50`, `p95`, `p99`, `max`, `count`.
- `StepStats` — one step's `count`, `ok`, `failures` by reason, and both
  timings.
- `RunResult` — `StepStats` by step name, plus `behind`: the scheduling delay
  the generator itself introduced.
- `RunRecorder` — the write side. A mutable accumulator per virtual thread,
  merged and frozen into a `RunResult` at the end, so recording a sample never
  contends on a shared lock.

## Why this shape

Two timings per step rather than one, always. Service time is what the target
did; response time is what a user would have seen given the load that was
promised. Reporting only the first is the omission; reporting only the second
hides whether the target or the generator was at fault. The gap between them
is the interesting number, so both are kept and `behind` names the cause.

Percentiles come from bucket boundaries and are never interpolated between
them. An interpolated percentile is a number nobody measured, and AGENTS.md
says a number in a report is a measurement or it is a lie. The cost is
precision, which is why the histogram states it and the report prints it.

The recorder is a mutable accumulator frozen before it escapes — the one shape
AGENTS.md allows — because the alternative, a shared concurrent structure, puts
a lock on the path being timed and makes the tool measure itself.

## Stack

- [ ] **`spec-0003-histogram`** — `Histogram`: log-linear buckets, `record`,
      `percentile`, `count`, `max`, and its stated precision.
      Done when: a recorded value reports a percentile within the stated
      precision of it, and the bucket count is flat across four decades.
- [ ] **`spec-0003-run-result`** — `Timing`, `StepStats`, `RunResult` and its
      `get(stepName)`, all read-only values.
      Done when: a hand-built `RunResult` answers p99 and failure counts, with
      no engine involved.
- [ ] **`spec-0003-recorder`** — `RunRecorder`, its per-thread accumulator and
      the merge that freezes it into a `RunResult`.
      Done when: two recorders merged report the same numbers as one recorder
      given both sets of samples.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. What precision, and what ceiling? Recommend two significant digits up to one
    hour: 1% error on a percentile is under the run-to-run noise of any real
    target, and the table stays small enough to inline in a report.
2. Failures are keyed by the reason string a step passed to `fail`. A scenario
    that interpolates an id into a reason makes thousands of keys. Recommend a
    cap on distinct reasons per step, with the rest counted under `"other"`,
    and the cap named in the report rather than silently applied.
3. Does `RunResult` keep the run's wall-clock start? Recommend yes: a report
    that cannot say when it ran is hard to compare against a deploy.
