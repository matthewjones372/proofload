# Expectations, goals and reading a result

There are three places to say what a run must achieve:

- **On the request**: `expecting(201)`, `checking("has an id") { ... }` and
  `capture(...)` decide whether each request succeeded. A request that fails
  one of them is a failed request with a named reason, and ends that user's
  journey. See [protocols.md](protocols.md#http) for the HTTP forms.
- **As goals on the simulation**: `expecting(p99(step) under 200.milliseconds, ...)`
  states objectives the run is judged against. The report shows each one, what
  was measured and by how much it missed.
- **As assertions in your test**, on the `RunResult` that `run` returns.

[measurement.md](measurement.md) defines every number used here.

## Assert, or declare goals

Assert when one number decides the test:

```kotlin
result[placeOrder].responseTime.p99 shouldBeLessThan 200.milliseconds
```

Declare goals when several do, or when you want the report to say which one
missed and by how much:

```kotlin
import io.github.matthewjones372.proofload.expecting
import io.github.matthewjones372.proofload.failureRate
import io.github.matthewjones372.proofload.goodput
import io.github.matthewjones372.proofload.keptSchedule
import io.github.matthewjones372.proofload.p99
import io.github.matthewjones372.proofload.percent

val result = proofload.run(
    checkout.at(50.perSecond, over = 10.minutes).expecting(
        p99(placeOrder) under 200.milliseconds,
        failureRate under 0.1.percent,
        goodput(placeOrder, under = 200.milliseconds) atLeast 99.percent,
        keptSchedule,
    ),
)
```

Each goal becomes a `Verdict` in `result.verdicts`, carrying the goal, whether
it was `met`, what was `measured` and, for a miss, `overBy`: the share of the
limit it missed by. "51% over" reads the same whether the limit was 200 ms or
two seconds. `result.metEveryGoal` is true when every verdict is met, and also
when there are no goals.

`goodput` is worth reaching for over a bare percentile: it is the share of
requests that both succeeded and came back in time, so a target that gets fast
by failing does not pass it.

`keptSchedule` guards the rest. It is met when `fellBehind()` is false: the
generator's departure lateness at p99 was no more than 5% of the slowest step's
response-time p99. Nothing adds it for you. It does not check `lostGround()`;
assert that separately if the delivered rate matters to the test.

Percentile, failure-rate and goodput goals are judged by `result.verdicts` over
the [steady segment](#read-only-the-part-that-settled) at the per-second
timeline's 6.25% precision. The JSON export, the CLI and the Java facade judge
them over the whole run. [measurement.md](measurement.md#goals-and-verdicts)
lists the cases where those can differ, and the cases (no goals, a run that sent
nothing) where goals are met without much being measured.

## Judge a goal where it was asked

A staged run's aggregate is a mixture. A goal judged against it can be met
because an easy stage pulled the total under the line:

```kotlin
import io.github.matthewjones372.proofload.expecting
import io.github.matthewjones372.proofload.hold
import io.github.matthewjones372.proofload.inEveryStage
import io.github.matthewjones372.proofload.injecting
import io.github.matthewjones372.proofload.p99
import io.github.matthewjones372.proofload.thenRampTo
import kotlin.time.Duration.Companion.milliseconds

checkout.injecting(hold(100.perSecond, over = 2.minutes).thenRampTo(1000.perSecond, over = 5.minutes))
    .expecting((p99(placeOrder) under 300.milliseconds).inEveryStage)
```

This produces one `Verdict` per stage, each naming its stage, and the run meets
the goal only where every stage does. The report puts each verdict on its stage
row.

Declare the plain goal beside it where you also want the aggregate, since it is the
number people compare between builds, and it is a different question:

```kotlin
.expecting(
    (p99(placeOrder) under 300.milliseconds).inEveryStage,
    p99(placeOrder) under 300.milliseconds,
)
```

`keptSchedule.inEveryStage` is worth declaring on a ramp: a run that kept its
schedule through the flat stage and lost it while climbing found the
generator's ceiling, and the aggregate hides that.

**A stage verdict can say it cannot tell.** A stage's numbers come from the
per-second buckets, which are 6.25% wide. A goal missed by less than that width
is reported as unresolvable rather than missed, it counts as met, and the
report says why.

## Ask what actually failed

A failure reason is a value, not a string, and the module that made the request
names it:

```kotlin
import io.github.matthewjones372.proofload.Said
import io.github.matthewjones372.proofload.Threw
import io.github.matthewjones372.proofload.TimedOut
import io.github.matthewjones372.proofload.http.CheckFailed
import io.github.matthewjones372.proofload.http.HttpStatus

result[placeOrder].failedWith(HttpStatus(503))        // the target said no
result[placeOrder].failedWith(TimedOut)               // it was up and too slow
result[placeOrder].failedWith(Threw("ConnectException"))
result[placeOrder].failedWith(CheckFailed("has an order id"))
```

Because reasons are typed, they can be grouped:

```kotlin
val serverErrors = result[placeOrder].failed.reasons
    .filterKeys { it is HttpStatus && it.code >= 500 }
    .values.sum()
```

A step body of your own uses `fail(String)` where it has no type to give, and
that is recorded as `Said`, so it groups like everything else:

```kotlin
exec(settle) { fail("ledger rejected it") }

result[settle].failedWith(Said("ledger rejected it"))
```

Declare your own where you have a type. Any value implementing `Reason` works.
Use a data class or an object, because reasons are map keys merged across
recorders and runs:

```kotlin
data class LedgerRejected(val code: String) : Reason {
    override val described: String get() = "ledger $code"
}
```

Past twenty distinct reasons on one step, the rest are counted under `Other`, so
a reason that embeds an id cannot grow without bound.

## Did the generator keep up?

Ask this before reading any latency:

```kotlin
result.fellBehind()   // generator lateness is a material part of the tail
result.lostGround()   // departures were late by more than the planned gap between them
result.behind.p99     // how late the late departures were
```

[measurement.md](measurement.md#the-schedule-checks) defines both checks. Where
either is true, response times include time spent queued inside the generator,
and a response-time goal that missed may have missed for that reason.

The run is not wasted. Service time was measured from when each step actually
started, so it still describes the target, at the load that reached it rather
than the load you asked for:

```kotlin
result.offered?.asked          // 2500/s: what the plan asked for
result.offered?.left           // 1923/s: what actually left
result.heldScheduleFor         // 11s: before the first second that lost ground
result.latePerSecond[12].p99   // how late that second's departures were
```

Re-run at a rate the machine held, or split the load across
[several injectors](more-than-one-injector.md), and read this run's service
times as the target at `left` in the meantime. Proofload does not lower the
rate mid-run. A [capacity search](#find-the-rate-it-sustains) is the supported
way to look for the highest rate, because every rung is a separate, labelled
run.

`arrivals` says the same thing from the other side: the spacing the run actually
produced, and its coefficient of variation.

This is also the run to change the clock on when comparing against a baseline.
`result.against(previous)` reads p99 of response time;
`result.against(previous, of = Clock.ServiceTime)` reads service time instead,
and the comparison records which clock it used. Java writes it as
`Results.against(result, previous, 99.0, Clock.ServiceTime)` and Scala as
`result.against(previous, of = Clock.ServiceTime)`. Choosing the clock is not in
`0.1.0-rc4`; see [versions.md](versions.md).

Scala reads the same checks as `result.fellBehind`,
`result.lostGround`, `result.ranOutOfRoom`, `result.concurrency` for Little's
law, and `result.offered` as an `Option[Offered]` whose `asked`, `left` and
`over` are the numbers above.

## Read only the part that settled

A run's first seconds are class loading, JIT and a cold cache. `steady` is the
segment after the run settled, as detected by
[`steadyState`](measurement.md#steady-state):

```kotlin
import io.github.matthewjones372.proofload.steady
import io.github.matthewjones372.proofload.steadyState

result.steadyState        // SteadyState.From(offset) or NeverSettled
result.steady[placeOrder].responseTime.p99
```

`result` still holds the whole run, and `steady` is the whole run where it never
settled. `result.verdicts` judges percentile, failure-rate and goodput goals over
`steady`. `keptSchedule` and per-stage goals are judged over the whole run,
because a second does not keep lateness or failure reasons.

## Find the rate it sustains

Rather than guessing a rate and asserting about it, ask for the highest one that
holds your goals:

```kotlin
import io.github.matthewjones372.proofload.engine.run
import io.github.matthewjones372.proofload.sustainable

val capacity = checkout.sustainable(
    upTo = 500.perSecond,
    holding = 2.minutes,
    expecting = listOf(p99(placeOrder) under 200.milliseconds, failureRate under 1.percent),
).run()

capacity.curve      // every rung that ran, with the run behind it
capacity.voided     // true if a rung was void: the answer is about this machine, not the target
```

It climbs a ladder of up to ten rungs, continues two rungs past the first that
missed a goal, then bisects between the last rung that passed and the first
that did not. The whole curve is kept.

Check `voided` before quoting the answer. A rung is void when the generator lost
ground or the process ran short of file descriptors or ephemeral ports; its
answer is about the machine you ran it on. `search.warmingUp(over)` warms each
rung at its own rate without recording the warm-up.

`Rate` is a value class, so the search is `Searches.sustainable(checkout, upTo,
holding, goals)` from Java and `checkout.sustainable(upTo, holding, goals*)`
from Scala, where `capacity.rate` is an `Option[Rate]` and `capacity.curve` a
`Seq[Rung]`.
