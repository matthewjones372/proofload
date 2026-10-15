# What a trustworthy result depends on

Proofload's results are only as good as a short list of properties. This page
states each one, what goes wrong without it, whether it holds today, and how it
is checked. Use it to decide how far to trust a result, and to know which
checks you have to make yourself.

Each property is in one of four states:

| State | Meaning |
|---|---|
| **holds, tested** | the implementation guarantees it, and an automated test fails if it stops holding |
| **holds, not tested at scale** | the implementation is built to guarantee it and ordinary tests exercise it, but no test pushes it at the concurrency where it would break |
| **partly** | it holds for some cases and not others; the gap is described |
| **does not hold** | the implementation does not guarantee it today; the gap is described, with what to do about it |

Benchmarks are a separate kind of evidence. They show what the generator
sustained on particular machines ([what-it-costs.md](what-it-costs.md)), not
that a property holds.

## The sixteen

| # | Property | What breaks without it | State |
|---|---|---|---|
| 1 | The arrival schedule is a pure function of the load profile and the arrival's index. No response, measurement or clock reading changes when a later arrival is due. | A slow target would slow the load down, which is coordinated omission. | holds, tested |
| 2 | Every arrival the profile names is either sent or counted as not sent. | Load could silently go missing, and the run would describe a lighter test than the one asked for. | partly |
| 3 | A backlog is never paced or smoothed. Arrivals that fell due while the generator was busy are started at once, and their lateness is recorded. | A generator that quietly spread out a backlog would hide its own queue. | holds, tested |
| 4 | Generator lateness is observable on every open run, in aggregate and per second. | There would be no way to tell a slow target from a slow generator. | holds, tested |
| 5 | Generator lateness is never reported as target latency: it is not folded into service time, and it is not subtracted from response time. | The two clocks would no longer separate the target from the generator. | partly: departure lateness only |
| 6 | Generator saturation, JVM saturation and target saturation can be told apart in the result. | A stalled generator thread reads as a slow target. | does not hold |
| 7 | A percentile from an invalid measurement cannot silently be treated as trustworthy. | A run that measured very little can be read with the same confidence as one that measured a lot. | does not hold |
| 8 | A run that was asked nothing has met nothing. | A test with no goals passes `metEveryGoal`. | does not hold |
| 9 | A run that sent nothing is never valid and never meets a goal. | A run of zero users can pass a failure-rate goal. | does not hold |
| 10 | A closed-model run is never presented as an open-model measurement. | A closed run passes every schedule check by construction. | partly |
| 11 | Every recorded observation is attributed to exactly one step, exactly once. | Counts and percentiles would be wrong per step. | holds, not tested at scale |
| 12 | Concurrent recording loses no observation and invents none. | Counts would drift under load, exactly when they matter. | holds, not tested at scale |
| 13 | "Not measured" can be told apart from zero wherever a number is reported. | An absent number reads as a perfect one. | partly |
| 14 | Nothing is estimated, smoothed or interpolated. A percentile is the upper bound of a counted bucket, and every number carries the width it was counted at. | A number could look more precise than the measurement behind it. | holds, tested |
| 15 | Session state is isolated between concurrent virtual users. | One user's captured value could be sent by another. | holds, not tested at scale |
| 16 | A cancelled or interrupted run cannot produce a result indistinguishable from a complete one. | A partial run could be read as a whole one. | does not hold |

## Where a property does not fully hold

**2: arrivals that are not sent are not all counted.** Every scheduled arrival
is started; none is dropped by the scheduler. But when a step fails, that user's
remaining steps are not run and not counted as anything. The HTML report states
the gap between planned and sent requests; the `RunResult` has no counter for
it. Compare `result[step].count` with the plan when it matters.

**5: only departure lateness is accounted for.** The lateness measured when a
user starts is added to every step that user records. Any delay *between* a
user's steps (for example, a virtual thread waiting for a carrier) is not
measured as lateness. See invariant 6 and
[measurement.md](measurement.md#response-time-for-every-step-of-a-journey).

**6: a stalled virtual thread reads as a slow target.** If a user's virtual
thread is descheduled inside a step, the wait is inside service time. `behind`
does not see it because it is measured when the user starts. `hiccups` does not
see it because it is measured on a platform thread. Little's law still agrees,
because both sides of it grow together. Treat very high concurrency on one JVM,
and JDBC drivers that pin carrier threads, with care; check `ranOutOfRoom()`.

**7: there is no validity flag on a measurement.** A percentile from five
samples is reported like one from five million. The exception is `p999`, which
is absent below 1,000 samples. Check `count` before trusting a percentile.

**8: no goals means `metEveryGoal` is true.** The JSON run document reports
`nothingAsked` in that case; `metEveryGoal`, the CLI exit code (0) and the test
framework modules do not distinguish it. Declare goals, including `keptSchedule`.

**9: an empty run can meet goals.** A profile whose rate times window is less
than one, such as `1.perSecond over 500.milliseconds`, has no users. Nothing
refuses it. Percentile and goodput goals then miss, but a run-wide `failureRate`
goal and `keptSchedule` are met. Assert that the steps you care about ran.

**10: closed runs are labelled only on the HTML page.** A closed run has no
schedule, so the schedule checks can never fail. The HTML report says so at the
top; the JSON document, markdown, CLI and MCP output do not.

**13: some absent numbers read as zero.** `p999`, Little's law and a few other
readings are explicitly absent when they cannot be measured. But a step that
recorded nothing has zero percentiles, and a run with no requests has a failure
rate of zero.

**16: a run cannot be cancelled.** There is no cancellation and no deadline. A
killed JVM produces no result at all, so a partial result cannot be mistaken for
a whole one, but nothing lets you stop a run and keep what it measured.

## How properties are checked

A test that defends a property says so in its KDoc ("Defends invariant 3" or
"Defends invariants 2 and 15"). The `invariants` task, which runs as part of
`./gradlew check`, lists every property with the tests that cite it, and fails
the build if a property that had a test loses its last one. It does not fail on
properties that have no test yet.

| # | Tests that cite it | What they check |
|---|---|---|
| 1 | `ScheduleArithmeticTest` | departure counts, the half-open window, ordering, determinism for the same seed |
| 2 | `ScheduleDeterminismTest`, `ShardOwnershipTest` | every departure is booked exactly once, in order; the injectors of a sharded run together send every user exactly once |
| 3 | `ScheduleArithmeticTest`, `ScheduleDeterminismTest`, `LatenessTableTest` | a late fill books its whole backlog at once and paces nothing |
| 4, 5 | `LatenessTableTest` | for runs built from known departure lateness and service time, the per-second lateness, the time the schedule held, the offered rate and the schedule checks all tell one consistent story, and lateness never changes the recorded service time |
| 14 | `ScheduleArithmeticTest` | partial arrivals are dropped, not rounded |
| 15 | `ShardOwnershipTest` | no two injectors send the same user |

Tests that exercise other properties without citing them include
`RecordersTest` (every sample survives concurrent recording, for 11 and 12),
`FeedingTest` (each user sends its own fed data, for 15), `HistogramAccuracyTest`
and `PercentileTest` (for 14), and `ScheduleDriftTest` (booking a run does not
make its own departures late, for 1 and 3).

## The release question

The project's own design notes recommend that `0.1.0` not ship with any
property in the "does not hold" state, or else that the claims made for the
tool be narrowed to match. That decision has not been made.
