# 0068 — Little's law on every run

## Problem

A run measures three quantities and never multiplies two to check the third: the
timeline's requests and both clocks a second (0025, 0049), and the in-flight count
the scheduler samples a second and throws away (0057). Over a run that starts and
ends empty `L = λW` is arithmetic, not a model, so they disagree only if one is
wrong — a clock timing what it does not claim, a generator that queued, a step
counted twice, a timeline that lost seconds. None of that shows up as a number
today: it shows up as a page that looks fine.

A suspect exists already: `Departures.starting()` runs when the pump *books* a
departure and `BOOKING_WINDOW` is five seconds, so `Snapshot.inFlight` counts up to
five seconds of users that have not left — 250 at 50/s, asserted against nothing.

## Not doing

- **No queueing model of the target.** `L = λW` is distribution-free; a
  utilisation law or a service demand is a model, and this tool has none.
- **No goal, and no failing a run by default.** See below.
- **No sampling on the request path.** The count is on the scheduler side, and
  the sampler ticks at one second already.
- **No new clock.** Both of 0003's are recorded.
- **Not over a completion step**, whose seconds are indexed by when the answer
  was observed, so its count is not an arrival rate.

## Shape

```kotlin
result.usersInFlight[30]      // 41 — the scheduler's own count, sampled in that second
result[pay].serviceTime.mean  // new: the mean, read off the buckets
val law = result.concurrency  // Concurrency.Measured, or Absent(because) like Tail
law.observed                  // 41.2  users running, averaged over the steady segment
law.fromServiceTime           // 39.6  throughput x mean service time
law.fromResponseTime          // 44.1  throughput x mean response time
law.ratio                     // 1.04  observed / fromServiceTime
law.backlog                   // 4.5   requests of queue the generator itself made
law.agrees                    // true, within the timeline's own precision
```

`Timing.mean` is `Σ count × upperBound / count` off `distribution`; `usersInFlight`
is a sample a second aligned with `timeline`, off the timed path. The page carries
the pair, the ratio and the backlog: a note where they agree, a warning where not.

## Why this shape

**Over a whole run the identity is exact, so a gap is a bug.** Three things spoil
it, all named already: requests that never finished, which `unanswered` flags; a
mean off bucket tops, high by at most the precision and never low; and a 1 Hz
measured side. Per second it is approximate, so the steady segment (0032) is judged.

**Both clocks, and the difference is the finding.** Service time is what is
outstanding on the wire, all the scheduler's counter can see; response time is the
load promised, and `responseTime = serviceTime + schedulingDelay` exactly — so
`fromResponseTime - fromServiceTime` is `λ × mean(behind)`, the backlog in requests.

**The measured side counts users**, which is requests in flight only while every
live user is inside a step, so a `Step.Pause` skips the gate with a reason. The
booking-window inflation goes first, or the check measures that and nothing else.

**A check, not a goal.** A failure says this tool's numbers do not add up, so every
other number is suspect; through `verdicts` it would read as the target's miss. A
red build may be right, but only with a message naming the tool — a warning's job.

**A list, not a field on `Second`.** `Second` is also every *step's* timeline, so
the field would mean nothing in most instances and `Runs.superimposed` would have to
invent a meaning for two runs' concurrency added. This overrides 0057's question 4.

## Stack

- [x] **`spec-0068-mean`** — `Timing.mean`, read off `distribution`.
      Done when: a timing of known samples reports a mean at or above their true
      mean and within its own precision of it, and a merged timing's is the
      merge's rather than the average of two.
- [x] **`spec-0068-departed`** — a user counted in flight when it departs rather
      than when it is booked.
      Done when: a run against a target slower than one departure interval reports
      rate × latency in flight, not rate × `BOOKING_WINDOW`, and `awaitAll` still
      cannot fire early.
- [x] **`spec-0068-sampled`** — `RunResult.usersInFlight`, from the existing
      one-second sampler, indexed by the second it was taken in.
      Done when: a ten-second run reports about ten samples, `Progress.silent` the
      same ones, a missed second is absent rather than zero, and the ceiling holds.
- [x] **`spec-0068-law`** — `Concurrency` over the steady segment: the ratio, the
      backlog, and the tolerance in one named place.
      Done when: agreeing quantities report `agrees`, a doubled latency reports the
      ratio and not `agrees`, a `Step.Pause` reports `Absent` with the reason, and
      a run that never settled says so rather than judging the whole run.
- [ ] **`spec-0068-page`** — the pair, the ratio and the backlog on the page and
      in the job summary.
      Done when: an agreeing page carries the note and its precision, a
      disagreeing one a warning naming the tool, and both goldens moved.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :examples:timingTests
```

## Open questions

1. **What tolerance?** ~~Asymmetric: `observed` above the prediction is a
    finding at any margin.~~ Built symmetric at `SteadyState.TOLERANCE`, and
    the recommendation was wrong about why. The derived side is indeed biased
    high — every mean is read off bucket tops — but the *measured* side is a
    one-hertz sample of a count that moves, and that error runs both ways. A
    one-sided gate fires on sampling noise and calls it a bug in the tool.
2. **How is `observed` averaged?** A sample a second is a sample of `L`, not its
    mean. Recommend the arithmetic mean of the steady segment's samples with the
    count printed, and no smoothing: an interpolated concurrency is the same lie
    as an interpolated percentile.
3. **Must a scenario with a pause be absent?** The think-time term is
    `λ_users × pause duration`, knowable under `Repeat` and not under `During` or
    `When`. Recommend `Absent` with the reason, and deriving it later only if a
    real scenario needs it.
4. **Does the timeline's index bias `fromResponseTime`?** A request is counted in the
    second it left, not the second it was due, so λ is one backlog out of step.
    Recommend accepting it: inside the tolerance whenever `fellBehind()` is false.
5. **Per arm in a mix?** Recommend not: that needs a second in-flight counter on
    the scheduler for a question nobody has asked.
