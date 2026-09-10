# The statements that must never become false

Proofload's claim is that its measurements can be trusted. This is the list of
things that have to hold for that to be true, and an honest statement of which
of them do not hold yet.

An invariant here is something whose falsity would make a run **untrustworthy**,
not something that would merely be a bug. Spec
[0134](../specs/0134-the-statements-that-must-never-become-false.md) argues for
each one and owns this page.

Five of sixteen do not hold. That number, not any sentence in the README, is
where 1.0 stands.

## The table

| # | Statement | Holds today | Owner |
|---|---|---|---|
| 1 | An arrival schedule is a pure function of the profile and the arrival's index. No response, measurement or clock reading changes when a future arrival is due. | yes | 0130, 0124 |
| 2 | Every arrival the profile named is either sent, or counted as not sent. An arrival never silently disappears. | partly | 0122, 0128 |
| 3 | Booking is never paced or smoothed. A backlog is discharged immediately and recorded as lateness. | yes | 0124 |
| 4 | Generator lateness is observable on every open run, per second and in aggregate. | yes | 0076, 0097 |
| 5 | Generator backlog is never reported as target latency. Lateness is never folded into service time nor subtracted from response time. | at departure only | 0003, 0122 |
| 6 | Load-generator saturation, JVM execution saturation and target saturation are distinguishable in the result. | **no** | 0126 |
| 7 | A percentile from an invalid measurement cannot silently be treated as trustworthy. | **no** | 0121 |
| 8 | A run that was asked nothing has met nothing. | **no** | 0121 |
| 9 | A run that sent nothing is never valid, and never meets a goal. | **no** | 0121, 0130 |
| 10 | A closed-model run is never presented as an open-model measurement. | partly | 0131 |
| 11 | Every recorded observation is attributable to exactly one step, exactly once. | yes, untested at scale | 0129, 0125 |
| 12 | Concurrent aggregation loses no observation and invents none. | yes, untested at scale | 0125, 0129 |
| 13 | Unmeasured is distinguishable from zero, everywhere a number is reported. | partly | 0129 |
| 14 | Nothing is estimated, smoothed or interpolated. A percentile is the top of a counted bucket, and every number carries the width it was counted at. | yes | 0003, 0047 |
| 15 | User and session state is isolated between concurrent virtual users. | yes, untested at scale | 0015, 0125 |
| 16 | Cancellation cannot produce a result indistinguishable from a complete run. | **no** | 0128 |

## What the five that do not hold mean for you

**6 — a slow carrier reads as a slow target.** A virtual thread descheduled
inside a step has that wait counted in `serviceTime`. `behind` stays flat, the
hiccup meter cannot see it because it ticks on a platform thread, and Little's
law agrees because both sides inflate together. Every gate is green and the
target looks slower than it is.

**7, 8 and 9 — a run can pass without measuring anything.** `metEveryGoal` is
true for a run with no goals. A profile like `1.perSecond over
500.milliseconds` names zero arrivals, and zero of zero failures satisfies a
failure-rate goal. Nothing makes a caller acknowledge that the number they are
reading came from a measurement worth reading.

**16 — a run cannot be stopped.** There is no cancellation and no deadline, and
a JVM exit loses everything the run measured.

## How an invariant is defended

A statement with no executable defence is a sentence, which is the thing this
page exists to replace. Each one ends up with at least one of:

- a **structural** defence, where it cannot be written wrong — 1, where
  `departures()` takes no argument that could carry a response;
- a **property test** with no clock in it (0124);
- an **adversarial experiment** against an oracle (0123);
- a **stress check** at scale (0125);
- a **result check** run at every freeze and merge (0129).

A test that defends one says so in its KDoc, in the words "Defends invariant N"
or "Defends invariants N and M". `./gradlew invariants` lists all sixteen with
the tests behind each:

```
 1  defended  ScheduleArithmeticTest.kt
 2  defended  ScheduleDeterminismTest.kt, ShardOwnershipTest.kt
 3  defended  LatenessTableTest.kt, ScheduleArithmeticTest.kt, ScheduleDeterminismTest.kt
 4  defended  LatenessTableTest.kt
 5  defended  LatenessTableTest.kt
 6  not yet   -
...
14  defended  ScheduleArithmeticTest.kt
15  defended  ShardOwnershipTest.kt
16  not yet   -
```

It runs from `check`, so the build goes red when a defended invariant loses its
last test, naming the number. It is a **ratchet, not a floor**: it does not fail
on the nine that have nothing behind them yet, because five of those describe
behaviour the tool does not have. An invariant that gains a test is added to
`Invariants.REQUIRED` in `buildSrc`; none may quietly lose one.

## The release rule

0134 recommends that 1.0 does not ship with a "no" in this table, and that if
any is unfixable in time the honest alternative is to weaken the README's claim
to match rather than to ship the claim over the gap. That recommendation has not
been accepted or rejected yet; it is the open question the size of the hardening
work depends on.
