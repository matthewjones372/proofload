# How Proofload measures

This page explains what each number in a result means, how the generator's own
lateness is detected, and what a run does and does not let you conclude. Read
it before you put a Proofload number in front of someone who will act on it.

## The problem: a generator can hide a slow target

A load test is meant to offer a target a stated workload, say 200 requests a
second, and report how the target responded. Two things can go wrong at the
generator, and both make a slow target look fast.

**The generator waits for an answer before sending the next request.** When the
target slows down, the generator sends less. The slow period is sampled less
often than the fast one, so the requests that would have shown the slowdown are
never sent and never measured.

**The generator falls behind its own schedule.** A request due at 10.000 s
leaves at 10.300 s because the generator was busy. If latency is measured from
10.300 s, the 300 ms the request spent queued inside the generator disappears.
A user would have felt it; the report does not show it.

Both are usually called **coordinated omission**: the measurement coordinates
with the system under test and omits exactly the samples that matter. The
result is a tail latency that looks healthy and a test that passes.

## What Proofload does about it

**Departures follow a schedule that responses cannot change.** In an open
workload (`at(rate, over)`, ramps, stages, Poisson arrivals, replays) each
user's departure time is computed from the load profile and the user's index
alone. A slow response does not delay the next user: each user runs on its own
virtual thread, and the scheduler starts it when its time comes whether or not
earlier users have finished.

**Lateness is recorded, not discarded.** When a user starts later than its
scheduled time, the difference is that user's *departure lateness*. It is
recorded in `result.behind` and per second in `result.latePerSecond`, and it is
added to the user's response times (below). A backlog is never paced or
smoothed: users that fell due while the generator was busy are started at once,
in order, and their lateness is recorded.

**Every step is timed on two clocks.**

```
                scheduled          actually
                departure          started          answer
                    |------ late ------|--- service ---|
                    |------------ response ------------|
```

| Clock | Starts | Stops | What it describes |
|---|---|---|---|
| **service time** | when the step body starts | when the step body returns or throws | what the step took once it was running |
| **response time** | service time **plus the user's departure lateness** | | what the step would have felt like to a user who arrived on schedule |

Goals read response time unless you ask for the other clock. A goal written
against service time can be met by a generator that never sent the load it was
asked to send.

**Every run measures whether the generator kept up.** The lateness is reduced
to two checks, `fellBehind()` and `lostGround()`, described
[below](#the-schedule-checks). You can make one of them a goal
(`keptSchedule`), and the HTML report, JSON document, CLI exit code and MCP
`status` all report the result. This is what lets a failed run say whether the
target missed its objective or the generator could not sustain the workload.

## What exactly is inside each clock

### Service time

Service time is the wall-clock time across the step body. Everything the body
does is inside it:

- building and sending the request, waiting, and reading the response;
- `expecting(...)` and `checking(...)`, and `capture(...)` extraction;
- redirects followed with `following()`, each hop counted in `attempts`;
- any code you wrote in an `exec(step) { ... }` block, including work that is
  not a call to the target.

The engine's own preparation (building the session and the step scope) is
outside it, and so is recording the sample afterwards.

Some modules report a figure of their own instead of one sample over the whole
body:

- **HTTP retries** (`retrying(...)`): the sample is the **last** attempt's
  duration. Earlier attempts and the backoff between them are counted in
  `attempts` and are not in the latency.
- **JDBC**: waiting for a pooled connection is recorded separately as
  `waitedForPool` (`queued` on the step) and is not in the query's service
  time.
- **Streams** (WebSocket `awaiting`, server-sent events, gRPC streams): one
  sample per message, each timed from what it answers or from the message
  before it. See [protocols.md](protocols.md).

A `pause` is not a step. It is in neither clock and records nothing.

### Response time, for every step of a journey

The departure lateness is measured once per user, when the user's virtual
thread starts, and the same value is added to **every** step that user records.

For a user's first step, response time is therefore exactly "from the scheduled
departure to the answer". For a later step it is that step's service time plus
the user's departure lateness. Time spent in earlier steps and in pauses is in
neither clock, and neither is any delay between steps after the user started.

So response time corrects for the generator starting a user late. It does not
model each later step as having a scheduled time of its own.

Two cases differ:

- **Work that completes elsewhere** (`emit` with `completing`, and Kafka
  completions): the completion is timed from the user's scheduled departure to
  the answer arriving. It includes every earlier step of that journey and has no
  separate service time.
- **A closed workload** (`users(n, over)`): a user has no scheduled departure,
  lateness is zero, and the two clocks are the same number. See
  [closed workloads](#closed-workloads).

## The schedule checks

`result.behind` is a histogram of departure lateness with one sample per
recorded request, so a user with three steps contributes its lateness three
times. Two checks read it:

| Check | True when | The question it answers |
|---|---|---|
| `fellBehind()` | `behind.p99` is more than 5% (`MATERIAL`) of the largest step response-time p99 | Is the generator's lateness large enough to visibly change the tail being reported? |
| `lostGround()` | `behind.p99` is more than the planned interval between departures (the profile's window divided by its user count, multiplied by the number of injectors) | Did the departures that left differ materially from the departures that were planned? |

They answer different questions and can disagree:

- At 150 requests a second the planned interval is 6.67 ms, so a `behind.p99`
  of 40 ms is lost ground: the load that left is not the load that was asked
  for.
- A `behind.p99` of 5 ms is immaterial beside a 356 ms response p99 but is most
  of an 8 ms one. Against a target answering in microseconds, `fellBehind()` is
  true at almost any rate, because almost any lateness is large compared with
  the response. That is a statement about the size of the lateness relative to
  the tail, not a statement that the generator struggled.
- The planned interval is the average over the whole profile. During a steep
  ramp the gap between departures is smaller than that average for part of the
  run.

Related readings:

| Reading | What it says |
|---|---|
| `result.behind.p99` | how late the late departures were |
| `result.offered` | `asked` and `left`: the rate the plan asked for and the rate that actually left. Null for a closed workload |
| `result.heldScheduleFor` | how long the run went before the first second whose lateness p99 exceeded the planned interval |
| `result.latePerSecond` | lateness per second, at coarse precision |
| `result.hiccups` | how late a 1 ms tick ran on the injector's JVM, measured on a platform thread that runs no requests. A tail of the same size as the target's is this machine, not the target |
| `result.ranOutOfRoom()` | whether this process came close to its limit on open files or ephemeral ports |

### Where each surface reports the schedule

The checks are always computed, but they are not all applied in the same
place. In particular, **nothing fails a run for falling behind unless you ask
it to.**

| Surface | What it does with the schedule |
|---|---|
| `keptSchedule` goal | met when `fellBehind()` is false. It does not consult `lostGround()`. You must declare it |
| `result.metEveryGoal` | reads only the goals you declared. With no goals it is `true` |
| JUnit, Kotest, zio-test modules | do not fail a test for falling behind. Assert it, or declare `keptSchedule` and assert `metEveryGoal` |
| HTML report | shows a "behind schedule" banner when `fellBehind()` is true |
| JSON run document (`proofload-export`) | `schedule.kept` is true only when neither check fired, and its `verdict` is `behind` ahead of any goal result |
| CLI `run` | exits 2 when the generator fell behind or lost ground, ahead of a missed goal (1) |
| MCP `status` | the same document as the JSON export |

The safe pattern in a test is to declare the schedule as a goal and to check
for lost ground as well:

```kotlin
val result = proofload.run(
    checkout.at(50.perSecond, over = 1.minutes)
        .expecting(p99(placeOrder) under 200.milliseconds, keptSchedule),
)

assertFalse(result.lostGround(), "the load that left is not the load that was asked for")
assertTrue(result.metEveryGoal, result.verdicts.joinToString())
```

### When the generator fell behind

The run is not wasted, but read it differently:

- Response times include the generator's queue. A response-time goal that
  missed may have missed because of the generator.
- Service times are still measurements of the target, at the load that
  actually reached it (`offered.left`), not the load you asked for.
- Re-run at a rate the machine held, or split the run across injectors
  ([more-than-one-injector.md](more-than-one-injector.md)). Proofload does not
  lower the rate for you mid-run; a [capacity search](goals-and-results.md#find-the-rate-it-sustains)
  is the supported way to look for the highest rate, and it marks a rung as void
  when the generator lost ground or the process ran short of file descriptors
  or ports.

## Percentiles

**p99 is the value 99% of samples were at or under.** One sample in a hundred
was slower. A page that makes six calls has roughly a one-in-seventeen chance
per page view of including at least one of them, which is why tails matter more
than averages.

Proofload's latencies are counted into histograms with the same bucket layout
as HdrHistogram: 256 sub-buckets per power of two, so a bucket is 1/128 of its
value wide. A percentile is the **upper bound of the bucket** holding the sample
at that rank. Nothing is interpolated. The consequence is that a reported
percentile is never below the true value and at most 0.78% above it; this width
travels with the number as `precision`.

| Reading | Precision | Notes |
|---|---|---|
| step `serviceTime` and `responseTime`, `behind`, `hiccups` | 0.78% | p50, p95, p99 and max are always present; they read zero when nothing was counted |
| `p999` | 0.78% | `Tail.Absent` below 1,000 samples, where the top bucket holds less than one request |
| per-second timeline, `latePerSecond`, the steady segment | 6.25% | 32 sub-buckets, so a long run does not hold tens of megabytes of counters |
| `mean` | | computed from bucket upper bounds, so never below the true mean |

Each step keeps ok and failed requests in separate histograms (`ok` and
`failed`). `serviceTime` and `responseTime` on a step are the two merged, so a
fast failure pulls a step's percentiles down. `goodput` reads only the ok side.

Results from several recorders, shards or runs are combined by adding bucket
counts and reading percentiles off the sum. Percentiles are never averaged.

## Failures

A failed step is counted, not thrown. It carries a `Reason`: a value such as
`HttpStatus(503)`, `TimedOut`, `Threw("ConnectException")`,
`CheckFailed("has an id")`, or `Said(...)` from `fail("...")` in your own step
body. An exception thrown from a step body is recorded as `Threw` with its class
name. The first reason recorded for a step wins. Past twenty distinct reasons
on one step, the rest are counted under `Other`.

**A failed step ends that user's journey.** The steps after it are not run and
are not counted. A step that needed an order id that was never created would
otherwise report a service answering nobody.

## Goals and verdicts

A goal is a value attached to the simulation with `expecting(...)`:

| Goal | Met when |
|---|---|
| `p50(step)`, `p95(step)`, `p99(step)`, `p999(step) under d` | that percentile of response time (or `of = Clock.ServiceTime`) is under `d` |
| `failureRate under share`, `failureRate(step) under share` | failed requests are under that share of all requests |
| `goodput(step, under = d) atLeast share` | at least that share of the step's requests both succeeded and finished under `d` |
| `keptSchedule` | `fellBehind()` is false |
| `(goal).inEveryStage`, for any of the above | it holds in every stage of a staged profile |

`result.verdicts` judges each goal and reports the measurement and, for a miss,
by what share it missed. Several things about judgement are worth knowing:

- **Over the steady segment.** `result.verdicts` and `result.metEveryGoal` judge
  percentile, failure-rate and goodput goals over the part of the run after it
  settled ([steady state](#steady-state)), read from the 6.25% per-second
  buckets. `keptSchedule` and `inEveryStage` are judged over the whole run. The
  JSON document, the CLI and the Java facade judge every goal over the whole
  run, so they can disagree with `result.verdicts` about the same run.
- **No goals means nothing was checked.** `metEveryGoal` is `true` for a run
  with no goals. The JSON verdict calls this `nothingAsked`; the CLI exits 0.
- **A run that sent nothing can meet goals.** A profile can name zero users:
  `1.perSecond over 500.milliseconds` truncates to none. Percentile and goodput
  goals then miss ("the step never ran"), but a run-wide `failureRate` goal is
  met (no requests, no failures) and so is `keptSchedule`. Check that the steps
  you assert on actually ran (`result.ran(step)`, `result[step].count`).
- **A per-stage goal can be unresolvable.** A stage's figures come from the
  coarse buckets; a miss smaller than the bucket width is reported as a refusal
  ("cannot tell") rather than a miss, and counts as met.

## Steady state

The first seconds of a run include class loading, JIT compilation, connection
setup and cold caches. `result.steadyState` looks for where the run settled: it
compares each second's service-time p99 across windows of a quarter of the run
and needs at least ten seconds of timeline. It answers `SteadyState.From(offset)`
or `NeverSettled` with a reason, including when the run got steadily slower.

`result.steady` is the run from that point, rebuilt from the per-second buckets.
It contains no lateness and no failure reasons, because a second does not keep
them. Where the run never settled, `steady` is the whole run.

## Little's law

For a stable system, requests in flight equal arrival rate times time in the
system. Proofload counts users in flight once a second and knows the throughput
and latencies, so it can check that the three agree. `result.concurrency`
reports, over the steady segment:

| Field | Meaning |
|---|---|
| `observed` | mean of the once-a-second count of users in flight |
| `fromServiceTime`, `fromResponseTime` | the counts the law predicts from each clock |
| `ratio` | `observed` over `fromServiceTime` |
| `agrees` | the ratio is within 12.5% of 1 |
| `backlog` | the gap between the two predictions, in requests |

A ratio that drifts from 1 means something is queueing where the measurement
does not see it: usually in the generator, sometimes in a connection pool. It
is `Absent`, with a reason, for scenarios that pause, because a paused user is
in flight without being in any step.

## What the machine can resolve

Running the same unchanging thing twice gives different answers. `calibrate()`
runs a step that does nothing at 5,000 a second, several times, and reports
how far the repeats landed from one another as a fraction (`resolution`),
along with the injector's hiccups and a probe that can be compared across
machines. It is bounded at 30 seconds and run once per JVM.

A comparison that knows the floor can answer **cannot tell** when a difference
is smaller than the machine can distinguish. See
[reports-and-baselines.md](reports-and-baselines.md).

## Closed workloads

`users(50, over = 10.minutes)` starts 50 users that each restart the scenario
when they finish. This is a closed model: when the target slows down, it sends
less, which is the coordinated omission described at the top of this page.

A closed run has no promised departures, so:

- `behind` and `latePerSecond` are empty, and the two clocks are equal;
- `offered` and `heldScheduleFor` are null, and declaring `keptSchedule` is
  refused when the simulation is built;
- the schedule checks are never true, so a closed run passes every schedule
  verdict by construction.

The HTML report says at the top that the run was closed. The JSON document,
markdown summary, CLI and MCP output do not label it. Little's law is the useful
check here, because the concurrency it predicts is the number you chose.

## What a run does not establish

A run whose schedule checks are clean tells you the generator started users on
time. It does not tell you every number is the target's. Known gaps:

- **A stalled virtual thread reads as a slow target.** If a user's virtual
  thread is descheduled during a step (for example, the carrier threads are all
  busy, or a JDBC driver pins its carrier), the wait is inside service time.
  `behind` does not see it, `hiccups` does not see it because it ticks on a
  platform thread, and Little's law still agrees. Read `ranOutOfRoom()` and the
  injector's limits, and treat very high concurrency on one JVM with care.
- **Lateness after departure is not modelled.** Response time adds the user's
  departure lateness to each step; it does not add delays between later steps.
- **Short and empty runs are not flagged as invalid.** A percentile from a
  handful of samples is reported like any other, except that `p999` is absent
  below 1,000 samples.
- **A user abandoned after a failure is not counted as anything** for the steps
  it did not reach. The HTML report states the shortfall between planned and
  sent requests; there is no counter for it in the result.
- **A run cannot be cancelled.** There is no deadline. A run that is killed
  produces no result.

[invariants.md](invariants.md) lists every property Proofload's results depend
on, which of them hold, and which tests defend them.

## Where these appear

| Idea | In the result | In a plan or report |
|---|---|---|
| both clocks | `responseTime`, `serviceTime` on every step | the report's clock toggle |
| departure lateness | `behind`, `latePerSecond`, `offered`, `heldScheduleFor` | "behind schedule" |
| schedule checks | `fellBehind()`, `lostGround()`, `keptSchedule` | JSON `schedule.kept`, CLI exit code 2 |
| percentiles | `p50`, `p95`, `p99`, `p999`, `max`, `precision` | `p99:` in a plan goal |
| Little's law | `concurrency` | the report's concurrency section |
| steady state | `steadyState`, `steady` | "settled after" |
| machine floor | `calibrate()`, `calibratedBy(floor)` | comparisons that say "cannot tell" |
| declared failures | `DeclaredStatus` | `declared:` in a plan |
| goodput | `goodput(step, under = ...) atLeast ...` | the report's goodput section |
