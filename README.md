<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/kestrel-mark-dark.svg">
  <img src="docs/assets/kestrel-mark-light.svg" width="88" height="88" alt="">
</picture>

# Kestrel

**Load testing for Kotlin, without the ceremony.** A scenario is an ordinary
Kotlin value: build it, inspect it, split it across files, run it.

</div>

> [!NOTE]
> Early, but it runs. Ten specs are built and green; nothing is released yet.
> See [AGENTS.md](AGENTS.md) before writing code.

```kotlin
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.engine.Kestrel
import io.github.matthewjones372.kestrel.http.http
import io.github.matthewjones372.kestrel.junit5.LoadTest
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.sessionKey
import io.github.matthewjones372.kestrel.step
import io.kotest.matchers.comparables.shouldBeLessThan
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

val orderId = sessionKey<String>("orderId")
val browse = step("browse")
val placeOrder = step("place order")
val api = http.baseUrl("https://orders.internal")

val checkout = scenario("checkout") {
    exec(browse, api.get("/products"))
    exec(
        placeOrder,
        api.post("/orders")
            .body("""{"cart":"1 anvil"}""")
            .expecting(201)
            .capture(orderId) { response -> response.header("location") },
    )
}

class CheckoutLoadTest {

    @LoadTest
    fun `checkout holds up at fifty a second`(kestrel: Kestrel) {
        val result = kestrel.run(checkout.at(50.perSecond, over = 1.minutes))

        result[placeOrder].responseTime.p99 shouldBeLessThan 200.milliseconds
        result.failed shouldBe 0L
    }
}
```

No session parameter to name, no result to remember to return, no cast to read
one back, and no step name written twice: a key carries its type and a step
handle carries its name, so a rename is a compile error rather than a test that
passes against a step nobody runs. `at` is Gatling's `setUp`, `inject` and
`protocols` in one call, and what it returns is an ordinary value —
`simulation.profile.userCount()` is 3000 before anything has been sent.

Every virtual user starts with its own data, so a cache in front of the target
cannot answer for all of them:

```kotlin
val customer = sessionKey<String>("customer")

val checkout = scenario("checkout") {
    exec(browse, api.get("/products/{customer}"))    // filled from the session
}

checkout.at(50.perSecond, over = 1.minutes)
    .fedBy(feed(customer) { user -> "customer-$user" })
```

A feeder is a function of the user's number rather than a cursor over a source,
so there is nothing to lock on the path every request takes, nothing to run out
of, and user 4,001 gets the same data tomorrow as it did today.

A load shape is stages in order, and still a value — so it composes, and it
answers before a request leaves:

```kotlin
val soak = rampRate(from = 0.perSecond, to = 200.perSecond, over = 1.minutes)
    .then(hold(200.perSecond, over = 10.minutes))
    .thenRampTo(0.perSecond, over = 1.minutes)

checkout.injecting(soak).profile.userCount()   // 132,000, before anything is sent
```

Those departures are evenly spaced, which no target ever receives. Real session
arrivals are close to Poisson, and queueing delay scales with how variable
arrivals are rather than only with their mean, so an even generator understates
queueing at the rate it says it is testing. `randomized` draws the arrivals
instead of spacing them:

```kotlin
val bursty = soak.randomized(seed = 20260826)

bursty.userCount() shouldBe soak.userCount()   // the count is exact, the spacing moves
bursty.over shouldBe soak.over
```

The seed has no default, because an unseeded random run is not one anybody can
reproduce. Each second of the shape gets the arrivals its rate line owed it,
placed where sorted uniforms fall, so a ramp still ramps and every departure
stays inside the window the profile promised. Each stage is seeded from the seed
and its own index, so a hold after a ramp does not repeat the ramp's draws and
the whole shape stays a function of the one seed.

Even spacing is still the default. Which of the two a run got is on the page as
a line rather than a warning, next to the spacing that was actually produced:

```kotlin
val result = kestrel.run(checkout.injecting(bursty))

result.arrivals.count   // departures the run made
result.arrivals.mean    // 5.00ms between them
result.arrivals.cov     // 0.98 — near 1.0 is a Poisson process, 0.0 is a metronome
```

Those three are measured from the departures that went out, not read back off
the profile that asked for them, which is the check the standard advice asks a
load test to make of itself. An even run reports a coefficient of variation of
zero and the report says what that costs: a p99 measured under even arrivals is
optimistic against the same mean rate in production.

Two latencies come back from every step. `serviceTime` is what the target took;
`responseTime` counts from the departure the profile promised, so a generator
that fell behind reports its own backlog rather than a fast target. When that
backlog is large enough to have moved a number, `result.fellBehind()` is true
and every report says so before it prints a percentile.

Every run also watches the machine it is sending from. A task due every
millisecond records how much later than that it actually ran, so a stall in the
measuring process arrives beside the tail it caused rather than inside it:

```kotlin
result.hiccups.p99   // 14ms, what the injector's own JVM stalled for
result.hiccups.max
```

The ticks run on an executor no departure and no step is ever submitted to, and
their histogram is read only once that executor has terminated, so watching
cannot move the numbers being watched. A tail no larger than `hiccups.p99` is
this machine as readily as the target, and both reports print the two together.

Before a report compares two runs it can ask what the machine underneath can
tell apart at all. A calibration runs the ordinary step machinery against an
action that does nothing — no socket and no target — and reports how far apart
repeats of that one unchanging thing landed:

```kotlin
import io.github.matthewjones372.kestrel.Floor
import io.github.matthewjones372.kestrel.engine.Kestrel
import io.github.matthewjones372.kestrel.junit5.LoadTest

class ResolutionTest {

    @LoadTest
    fun `what this machine can tell apart`(kestrel: Kestrel) {
        val floor: Floor = kestrel.calibrate()

        floor.resolution       // 0.061 — a difference under 6.1% is this machine
        floor.hiccups.p99      // 14ms — what the injector itself stalled for
        floor.resolves(0.03)   // false: a 3% difference is not resolvable here
    }
}
```

`resolution` is measured at the median, where the statistic is the machine's own
throughput and a fraction of it still means something at another scale. It bounds
the *size* of a change; a claim about a tail has to clear `hiccups.p99` in
absolute terms as well, which is the other half of what one calibration measures.

The floor is a property of the machine rather than of a run, so it is measured
once per JVM and kept, bounded at thirty seconds. On a runner somebody has
already characterised, `-Dkestrel.resolution=0.02` names it instead of measuring
it again. Hand it to a report and the page says what it can resolve; where the
floor is too large for any latency claim to rest on, the page says that where
the comparison would have gone rather than printing one nobody should act on:

```kotlin
import io.github.matthewjones372.kestrel.against
import io.github.matthewjones372.kestrel.report.writeHtmlReport
import java.nio.file.Path

result.writeHtmlReport(Path.of("build/reports/kestrel.html"), result.against(baseline), floor)
```

A null step is what makes that number mean something. Reading the floor off the
run's own variance instead would fold the target's variability into it, and a
genuinely erratic target would raise its own noise floor and hide its own
regressions.

A timing carries the buckets it was read from, so it answers a percentile
nobody asked for while the run was going — `p999` among them, which is where
two JVM collectors that match to p99 come apart:

```kotlin
import io.github.matthewjones372.kestrel.Tail

result[placeOrder].serviceTime.percentile(99.95)      // any percentile, off the buckets

when (val tail = result[placeOrder].serviceTime.p999) {
    is Tail.Measured -> tail.duration
    is Tail.Absent -> tail.because   // "only 400 samples, and under 1000 …"
}
```

A run of four hundred requests has not measured one request in a thousand, so
`p999` answers with the reason rather than with a number nobody measured. A
goal can name the tail as readily — `p999(placeOrder) under 1.seconds`, beside
`p50`, `p95` and `p99` — and a run too short to have measured one misses that
goal carrying the same reason, rather than passing on a percentile it never
reached. The HTML report prints each step's tail with the 95% sampling
interval around it, which is the width a number resting on one request in a
thousand has.

Nobody picks a rate because they want to know about that rate. To ask the
question they actually have — what can this take? — hand a search the ceiling
you consent to and what good looks like at it:

```kotlin
import io.github.matthewjones372.kestrel.failureRate
import io.github.matthewjones372.kestrel.p99
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.percent
import io.github.matthewjones372.kestrel.sustainable
import io.github.matthewjones372.kestrel.engine.run
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

val search = checkout.sustainable(
    upTo = 10_000.perSecond,
    holding = 2.minutes,
    expecting = listOf(p99(placeOrder) under 200.milliseconds, failureRate under 1.percent),
)

search.rungs        // the rates it will try, before anything is sent
search.worstCase    // 30m of holds, so nobody starts this by accident

val capacity = search.run()

capacity.rate       // the highest rate every goal held at
capacity.limitedBy  // the goal that stopped it
capacity.curve      // every rung: its rate, its verdicts and its result
```

It climbs a coarse ladder and then bisects between the last rung that passed
and the first that did not, so the resolution goes where the knee is and
nothing is spent on the flat left-hand side. It carries on two rungs past the
first failure, because the shape past the knee is what says whether the target
sheds load or collapses.

A rung where the injector fell behind is **void** rather than failed: the load
was never offered, so nothing was learned about the target, and the search
stops rather than publish the generator's own ceiling under the target's name.
`capacity.voided` says that happened, and `capacity.rate` is then a floor the
generator reached rather than a ceiling the target could not pass.

`capacity.toHtmlReport()` puts the whole curve on one self-contained page —
every rung, what it was judged to be, and the operating point marked on the
chart and in the table.

The same buckets answer the other question a team promised its users: what
share of the requests came back inside the target at all.

```kotlin
import io.github.matthewjones372.kestrel.Met

when (val met = result[placeOrder].responseTime.share(under = 200.milliseconds)) {
    is Met.Measured -> met.fraction   // 0.994
    is Met.Absent -> met.because      // "nothing was recorded, …"
}
```

The bucket the target falls inside counts as having missed it, in the direction
every percentile here already rounds: a share is never larger than the share
that really met the target, so it is a number that can be quoted. A step that
recorded nothing says so rather than reporting a zero somebody reads as a
service that met nothing.

A share of requests and a rate of them are the two halves of the same promise,
so `goodput` answers both: the requests that succeeded *and* came back inside
the target, and what that comes to per second.

```kotlin
import io.github.matthewjones372.kestrel.goodput
import io.github.matthewjones372.kestrel.percent
import kotlin.time.Duration.Companion.milliseconds

result[pay].met(under = 200.milliseconds)     // Met.Measured(0.98)
result.goodput(under = 200.milliseconds)      // 4,973/s across every step

checkout.at(5_000.perSecond, over = 2.minutes)
    .expecting(goodput(pay, under = 200.milliseconds) atLeast 99.percent)
```

The rate is over the window the plan asked for rather than the span the run
took, which is what makes two runs comparable; a run that did not keep to its
window is already saying so through `fellBehind()`. A histogram counts failed
requests beside successful ones, so a request that missed the target is charged
against the ones that succeeded: the share is the lowest the two counts allow,
and the report says as much where it prints it. A user abandoned after a failed
step counts against the step that failed, and not again against the steps it
never reached.

Not every answer comes back where it left. Publish a record to a topic and the
answer appears at a sink, in another process, seconds later. An `emit` step
departs and does not wait, and the run drains the sink the simulation names:

```kotlin
import io.github.matthewjones372.kestrel.InMemoryCompletions
import io.github.matthewjones372.kestrel.action
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.completing
import io.github.matthewjones372.kestrel.engine.run
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.sessionKey
import io.github.matthewjones372.kestrel.step
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

val tradeId = sessionKey<Long>("tradeId")
val submitted = step("submitted")
val settled = step("settled")

// Until a module carries a broker, the sink is in this process: the scenario
// hands it the ids it published, and the run drains it as it would a topic.
val settlements = InMemoryCompletions()
val ids = AtomicLong()

val trades = scenario("trades") {
    emit(
        submitted,
        action {
            val id = ids.incrementAndGet()
            set(tradeId, id)
            settlements.observe(id)
        },
        keyedBy = { session -> session[tradeId] ?: 0L },
    )
}

val result = trades.at(5_000.perSecond, over = 5.minutes)
    .completing(settled, from = settlements, drainingFor = 30.seconds)
    .run()

result[settled].serviceTime.p99   // the sink's observation, minus the intended departure
result[settled].unmatched         // 41 records never arrived
result[settled].inFlight          // 12 left too late to be given the whole wait
```

The latency is the sink's observation minus the departure the profile promised,
never minus the publish: measured from ingestion a pipeline's latency stays
flat while the system falls apart, which is why it is the number every
dashboard shows. The publish is timed too, under the emit step, because a slow
producer and a slow pipeline are different problems and adding them together
hides both.

A record that never arrives is the finding rather than a missing sample, so it
is counted rather than dropped — and counted apart from one the run simply did
not wait for. `drainingFor` is required and has no default: it is the line
between the two, and choosing it for you would move records across that line
without saying so. `result.fellBehind()` stays what it always was, the
injector's own backlog; the pipeline falling behind is what the latency above
measures.

## Worse than last time?

`kestrel-baseline` keeps a run in a file so the next one can be compared to it.
The file carries the buckets rather than five percentiles — an interval cannot
be rebuilt from those — and the plan and machine the run was measured under:

```kotlin
import io.github.matthewjones372.kestrel.Change
import io.github.matthewjones372.kestrel.Comparison
import io.github.matthewjones372.kestrel.against
import io.github.matthewjones372.kestrel.baseline.readBaseline
import io.github.matthewjones372.kestrel.baseline.writeBaseline
import java.nio.file.Path

val baseline = Path.of("build/kestrel/checkout.kestrel")

when (val comparison = result.against(readBaseline(baseline))) {
    is Comparison.NotComparable -> println(comparison.why)
    is Comparison.Compared -> {
        comparison.caveat?.let(::println)
        comparison.changes.forEach { change ->
            when (change) {
                is Change.Worse -> println("${change.step}: ${change.before} → ${change.now}")
                is Change.Better, is Change.Indistinguishable, is Change.Added, is Change.Gone -> Unit
            }
        }
    }
}

result.writeBaseline(baseline)
```

A run of a different plan is not compared at all: `NotComparable` names what
differs — the scenario, its steps or its rate line — because comparing a smoke
run to a soak undoes every interval and bucket underneath it, and no statistics
rescue it. A run on a different machine *is* compared, with `caveat` naming the
two runners and saying every delta may be one of them: a team whose runners are
all shared would otherwise never get a comparison at all, and they should get
one with the caveat attached.

Hand the comparison to the report and the page carries it — including a refusal,
which is the difference between a first run and a cache key that broke:

```kotlin
import io.github.matthewjones372.kestrel.report.writeHtmlReport

result.writeHtmlReport(Path.of("build/reports/kestrel/checkout.html"), comparison)
```

## What this is for

Gatling is the reference point and the thing to be simpler than. Its scenario
DSL is Scala, its reports are a bundled web app, and running one in CI means
adopting its plugin and its conventions. The bet here is that most teams want
a much smaller slice: describe a scenario in Kotlin, run it from a test or a
`main`, get numbers that are honest about what they measured.

The three decisions that shape everything else, and are still open:

- **What a scenario is.** A value, not a builder that runs as it is called —
  so it can be composed, filtered, printed and compared before anything is
  sent.
- **What runs it.** Whatever engine the first spec argues for, behind an
  interface core declares, so the description does not belong to it.
- **What honest numbers mean here.** Coordinated omission is the default bug
  in this class of tool; where the design admits it, it gets said out loud
  rather than smoothed over.

## Building

```bash
./gradlew build          # tests, detekt, spotless, coverage
./gradlew spotlessApply  # and run this last, before you commit
```

## Layout

| Module | Depends on | For |
|---|---|---|
| `kestrel-core` | **nothing** | scenarios, profiles and results as values |
| `kestrel-engine` | core | virtual threads, departures on a schedule |
| `kestrel-http` | core | HTTP steps on `java.net.http` |
| `kestrel-junit5` | core, engine, JUnit | a load test that is a `@Test` |
| `kestrel-kotest` | core, engine | the same, in a Kotest spec |
| `kestrel-baseline` | core | a run kept in a file, to compare the next one to |
| `kestrel-report-html` | core | one self-contained, interactive HTML file |
| `kestrel-report-github` | core | markdown, a job summary, a Pages index |
| `kestrel-pelican` | core, `pelican-core` | [Pelican](https://github.com/matthewjones372/pelican) endpoints as steps |

Every row is a test, not a promise: each module asserts its own runtime
classpath, so core cannot grow a dependency and the Kotest module cannot
quietly start needing the JUnit one.

Core depends on the Kotlin standard library and nothing else, and a test says
so. Everything with a third-party type in it becomes a leaf module beside it.

## License

Apache 2.0 — see [LICENSE](LICENSE).
