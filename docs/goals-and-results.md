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
result.refused        // arrivals refused at an inFlightAtMost cap, never sent
result.generatorSaturated()   // any of the above, or the process ran out of room
```

[measurement.md](measurement.md#the-schedule-checks) defines both checks, and
[the cap](measurement.md#a-cap-on-users-in-flight) explains refusals. A run that
refused any arrival did not offer the load it asked for, and is void. Where
either check is true, response times include time spent queued inside the generator,
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

## Catch a leak with JFR beside the soak

Proofload sees the target from outside: latency, counts, failures. A leak is
memory that grows while the load stays flat, and only the target's own JVM can
say that. So the test holds the load still with proofload and asks Java Flight
Recorder for the heap at both ends of the soak:

```kotlin
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.engine.Proofload
import io.github.matthewjones372.proofload.http.exec
import io.github.matthewjones372.proofload.http.http
import io.github.matthewjones372.proofload.junit5.LoadTest
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.scenario
import io.kotest.matchers.longs.shouldBeLessThan
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import jdk.jfr.consumer.RecordingFile
import kotlin.io.path.createTempDirectory
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class OrdersLeakTest {

    private val api = http.baseUrl("http://localhost:8080")

    private val checkout = scenario("checkout") {
        exec(browse, api.get("/products"))
        exec(placeOrder, api.post("/orders").body("""{"cart":"1 anvil"}""").expecting(201))
    }

    @LoadTest
    fun `an hour of checkout leaves the heap where it found it`(proofload: Proofload) {
        val jfr = createTempDirectory("leak").resolve("orders.jfr")
        val orders = ProcessBuilder(
            "java",
            "-XX:StartFlightRecording=filename=$jfr,settings=profile,dumponexit=true,path-to-gc-roots=true",
            "-jar", "build/libs/orders.jar",
        ).inheritIO().start()
        awaitUp("http://localhost:8080/health")

        proofload.run(checkout.at(50.perSecond, over = 2.minutes))   // warm: JIT, pools, caches
        fullGc(orders)
        proofload.run(checkout.at(50.perSecond, over = 1.hours))
        fullGc(orders)

        orders.destroy()                                             // a clean exit writes the recording
        orders.waitFor(1, TimeUnit.MINUTES)

        val (before, after) = liveHeapAtEachFullGc(jfr).let { it.first() to it.last() }
        (after - before) shouldBeLessThan 32L * 1024 * 1024
    }
}

private fun fullGc(target: Process) {
    val jcmd = Path.of(System.getProperty("java.home"), "bin", "jcmd").toString()
    ProcessBuilder(jcmd, target.pid().toString(), "GC.run").inheritIO().start().waitFor()
}

/** Heap used after each collection that `jcmd GC.run` asked for, in the order they ran. */
private fun liveHeapAtEachFullGc(jfr: Path): List<Long> {
    val events = RecordingFile.readAllEvents(jfr)
    val asked = events
        .filter { it.eventType.name == "jdk.GarbageCollection" && it.getString("cause") == "Diagnostic Command" }
        .map { it.getInt("gcId") }
        .toSet()
    return events
        .filter { it.eventType.name == "jdk.GCHeapSummary" && it.getString("when") == "After GC" }
        .filter { it.getInt("gcId") in asked }
        .sortedBy { it.startTime }
        .map { it.getLong("heapUsed") }
}

private fun awaitUp(url: String) {
    val client = HttpClient.newHttpClient()
    val ping = HttpRequest.newBuilder(URI(url)).build()
    repeat(120) {
        try {
            if (client.send(ping, BodyHandlers.discarding()).statusCode() == 200) return
        } catch (notYet: ConnectException) {
            // not listening yet
        }
        Thread.sleep(500)
    }
    error("$url never came up")
}
```

```groovy
dependencies {
    testImplementation("io.github.matthewjones372:proofload-junit5:0.1.0-rc4")
    testImplementation("io.github.matthewjones372:proofload-http:0.1.0-rc4")
    testImplementation("io.kotest:kotest-assertions-core:6.2.4")
}
```

**Why a full collection at each end, and not the heap over time.** Heap used
between collections is mostly garbage nobody has collected yet, and even the
reading after a young collection still carries old-generation garbage waiting
for a concurrent cycle. A collection that `jcmd GC.run` asks for is a full
one, recorded with the cause `Diagnostic Command`, so what is left after it
is what is still reachable. A target started with
`-XX:+ExplicitGCInvokesConcurrent` runs a concurrent cycle instead, and the
reading is no longer a floor. Two of those
readings, before and after an hour of the same load, are a measurement: nothing
fitted, no slope. The warm-up comes before the first one so that caches and
pools filling up count as the baseline rather than as growth.

**Why the target is its own process.** Run the target inside the test JVM and
the heap holds proofload's histograms and timeline too. They are bounded
([what it costs](what-it-costs.md)), but a leak verdict should not have to
subtract anything.

**The threshold is yours.** 32 MB is a placeholder. Set it from a run you
trust: a target that does not leak still moves its live set by a few megabytes
between two full collections, and that wobble is the floor, as it is for
[latency](reports-and-baselines.md#more-than-one-run-and-a-verdict-worth-having).

**When it fails**, the culprit is already in the recording.
`path-to-gc-roots=true` makes the recorder keep a sample of old, still-live
objects with the stack that allocated each one and the chain of references
keeping it alive:

```bash
jfr print --events jdk.OldObjectSample orders.jfr
```

Or open the file in JDK Mission Control, whose Memory page groups the same
samples by class. Walking the roots costs a pause when the recording is
written, which is why it happens once, at exit, after the load has stopped.

A leak shows up in the proofload result too, though it does not prove one: p99
climbing through `result.timeline` while the rate stays flat, or a
`result.steadyState` of `NeverSettled`. To watch both sides live during a long
soak, [push the run to a collector](running.md#watch-a-two-hour-soak-from-a-dashboard)
that already scrapes the target's JVM metrics.

`destroy()` asks the process to stop. On Windows it kills the process
instead, and no recording is written; run `jcmd <pid> JFR.dump` before
stopping it there.
