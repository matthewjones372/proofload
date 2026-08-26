# 0033 — The share that met the target

## Problem

A run reports how many requests failed and where the tail sits, and neither
answers the question a team actually promised their users. A step where every
request succeeded in four seconds reads as clean. A run at 5,000 a second where
8% of requests missed the target reads the same as one where none did.

The missing number is goodput: throughput counted only over the requests that
succeeded and came back inside the target `[GOODPUT]`. It is what separates a
system that is busy from one that is stuck, and it is the shape of a production
latency SLI, good events over total events `[SLI]` — so a benchmark and the
dashboard a team already watches can be the same number.

0031 also needs it. A search over rates needs an accept predicate that is cheap
per rung and reads the same way a service-level objective does.

## Not doing

- No error budget arithmetic. A share is a share; burn rates belong to whatever
  watches production.
- No goodput over time. 0025 owns the timeline; a share per second is a line on
  it later, not a value here.
- No second definition of failure. What counts as failed is 0003's, extended by
  0026's checks.

## Shape

```kotlin
result[pay].serviceTime.share(under = 200.milliseconds)   // 0.994
result[pay].goodput(under = 200.milliseconds)             // 4,973/s
result.goodput(under = 200.milliseconds)                  // across every step

val simulation = checkout.at(5_000.perSecond, over = 2.minutes)
    .expecting(goodput(pay, under = 200.milliseconds) atLeast 99.percent)
```

- `Timing.share(under)` — the fraction of samples at or below a duration,
  counted from the buckets already kept.
- `StepStats.goodput(under)` and `RunResult.goodput(under)` — a rate: successful
  requests inside the target, over the window the plan asked for.
- `Goal.GoodputAtLeast` — the goal form, beside 0020's `under` builders.

A share reads **response time** unless told otherwise, for the same reason
0020's percentile goals do: a share of service times can be met by a generator
that never sent the load.

## Why this shape

The bucket holding the target is where the count is approximate. Recommend
counting that whole bucket as **missing** the target, in the same direction
`percentile` already rounds: an approximation that cannot flatter is the one
that can be quoted. At 0.78% precision the bucket is narrow enough that the
error is smaller than the noise on any runner this will run on `[CINOISE]`.

Goodput is a rate, so it needs a window. Recommend the planned window from the
`Plan` rather than the observed span: the planned window is what makes two runs
comparable, and a run that did not keep to it is already flagged by
`fellBehind()`. The page should print both the share and the rate, because the
share is the promise and the rate is the capacity.

## Stack

- [ ] **`spec-0033-share`** — `Timing.share(under)` off the buckets, with the
      rounding direction fixed and documented.
      Done when: a timing whose samples all sit below the target reports 1.0, a
      target inside a bucket counts that bucket as missing, and an empty timing
      reports absent rather than zero.
- [ ] **`spec-0033-goodput`** — the rate on `StepStats` and `RunResult`, and the
      `atLeast` goal.
      Done when: a run with 1% failures and 1% slow responses reports 98%, and
      the goal judges and renders like every other.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Is goodput per step or per run the headline?** Recommend per run on the
    page and per step on demand: a user's journey succeeded or it did not, and
    the per-step number is the diagnosis rather than the promise.
2. **Does an abandoned user count against goodput?** Recommend yes, at the step
    that failed, and not again at the steps that never ran. Counting a payment
    that never had a cart is 0004's rule and this should not reopen it.
3. **Planned window or observed span?** Recommended above, but it is a real
    fork: the observed span is more honest about what happened, the planned
    window is what makes two runs comparable. If they differ materially the run
    fell behind and the page already says so.
