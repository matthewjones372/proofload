# 0036 — A slow error is not a fast one

## Problem

`StepStats` holds one `serviceTime` and one `responseTime` over every sample,
and a `failures` map that counts reasons without timing them. A service shedding
load answers a large share of requests with an immediate rejection, and those
fast failures pull the whole distribution down: the run reports a p99 nobody
experienced, made mostly of errors, and the better the shedding the better the
number looks.

This is the one case where the present model reports a good run for a target
that is falling over, which is the case a load test exists to catch. Standard
practice is explicit about it: track failed-request latency separately, because
a slow error is worse than a fast error `[SIGNALS]`.

0026 makes more responses count as failures, which makes this worse before it
makes it better: the more accurately failures are detected, the more of them
land in the distribution that is supposed to describe success.

## Not doing

- No histogram per failure reason. That is a memory multiplier for a question
  nobody asks first, and `failures` already names what went wrong.
- No change to what counts as a failure, or to abandoning a user after one.
- No new goals. A goal against the successful distribution alone is a later
  spec if anyone wants one.

## Shape

```kotlin
result[pay].serviceTime            // every sample, unchanged
result[pay].ok.serviceTime         // the requests that worked
result[pay].failed.serviceTime     // the requests that did not
result[pay].failed.count           // was `result[pay].failed`
result[pay].failed.reasons         // was `failures`
```

- `Outcome` — a count, a `serviceTime`, a `responseTime`, for one side.
- `StepStats.ok` and `failed` become `Outcome`; `count` stays the sum, and the
  whole-step timings stay where they are.

This renames public fields. `result[pay].failed shouldBe 0L` becomes
`result[pay].failed.count shouldBe 0L`, which is in the README, the examples
module and both test-framework modules. Nothing is released, so this is the
cheapest it will ever be.

## Why this shape

The alternative leaves `ok` and `failed` as counts and adds `okTiming` and
`failedTiming` beside them: nothing breaks, and four fields describe two things.
A value carrying a count and the distribution behind it is the shape that reads,
because `failed.count` and `failed.serviceTime.p99` are the two questions
anybody asks in sequence.

The cost is memory on the recording path. `RunRecorder` keeps two histograms per
step at about 43 KB; this makes four, so a ten-step scenario at the default
shard count moves from roughly 3.4 MB to 6.9 MB. That is a run's worth of
memory, not a request's, and nothing on the timed path allocates. 0025's coarse
histogram is the precedent for spending less where less is needed, if it turns
out to matter.

## Stack

- [ ] **`spec-0036-outcome`** — `Outcome`, the split in `RunRecorder`, and
      `StepStats.ok` and `failed` as values.
      Done when: a run whose failures are all fast reports a low failed p99 and
      an unchanged ok p99, and the whole-step timing still equals the merge of
      the two.
- [ ] **`spec-0036-callers`** — README, examples, report, JUnit and Kotest
      modules.
      Done when: the build is green and the golden shows both distributions.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Does the page show both by default?** Recommend showing the failed
    distribution only when there are failures, and then prominently. A panel of
    zeroes on every clean run teaches people to skip the panel.
2. **Should goals judge the successful distribution?** Recommend no, and say so:
    a p99 over successes only can be met by a target that failed most of the
    load, which is the mirror of the bug this spec fixes.
3. **Does `failureRate` change?** No. It is a count over a count and is
    unaffected, but the spec should say so, because the rename makes it look as
    though it might.
