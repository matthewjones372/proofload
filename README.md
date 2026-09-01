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
> Early, but it runs. `specs/` records what is built and what is not; nothing is
> released yet. Read [AGENTS.md](AGENTS.md) before writing code.

> [!TIP]
> [docs/cookbook.md](docs/cookbook.md) is the recipe book: feeders, ramps, think
> time, cookies, WebSockets, asynchronous completions, goals, capacity searches,
> steady state, reports, and CI. Each recipe is the handful of lines you would
> actually write, with a note on why they are those lines and not the obvious
> alternative.

## A first example

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

Two ideas run through this example.

The first is that everything is typed and named once. `orderId` is a
`sessionKey<String>`, so what you capture into it reads back as a `String` with
no cast. `browse` and `placeOrder` are step handles, so the name you assert
against is the same object you ran; rename a handle and the code stops
compiling, instead of leaving a passing test that checks a step nobody runs.

The second is that a scenario is a plain value. `at` does in one call what
Gatling splits across `setUp`, `inject` and `protocols`, and what it returns is
something you can inspect. `checkout.at(50.perSecond, over = 1.minutes).profile.userCount()`
is 3000 before a single request goes out.

## Runs are exclusive

A run holds the machine for as long as it lasts. If two tests start a run at the
same moment, they take turns rather than run at once and measure each other's
load, and a capacity search holds that lock across every rung of its curve. The
lock is per JVM: a second process on the same host is not queued behind it.

## Per-user data

Give each virtual user its own data, so a cache in front of the target cannot
serve one cached answer to all of them:

```kotlin
val customer = sessionKey<String>("customer")

val checkout = scenario("checkout") {
    exec(browse, api.get("/products/{customer}"))    // filled from the session
}

checkout.at(50.perSecond, over = 1.minutes)
    .fedBy(feed(customer) { user -> "customer-$user" })
```

A feeder is a function of the user's number, not a cursor over a source. Nothing
has to be locked on the path every request takes, nothing runs out partway
through, and user 4,001 gets the same data tomorrow that it got today.

## Sessions: cookies and redirects

A target with a login form answers with a cookie, and `withCookies()` carries
that cookie from the step that received it to the steps after it:

```kotlin
val api = http.baseUrl("https://shop.internal").withCookies()

val signedIn = scenario("signed in") {
    exec(signIn, api.post("/session").body(credentials).expecting(302))
    exec(browse, api.get("/account"))    // carries the cookie the sign-in set
}
```

Each user keeps its own jar. There is one shared `HttpClient` for the whole run,
so TLS handshakes are not measured, and `java.net.CookieHandler` hangs off the
client rather than off a user. A jar there would be one jar shared by every
user: fifty thousand people taking turns being one logged-in person. So Kestrel
stores name and value per session and nothing else. There is no expiry and no
path or domain matching, because a load test sends to a single base URL.

Cookies are off unless you ask for them, so a scenario without `withCookies()`
sends no cookie header at all. Redirects are off by default too. A 302 stays the
failure it was, recorded under the step that received it, until `following()`
says otherwise.

## Think time

A real user reads the page before clicking again. `pause` models that gap. Like
everything else in a scenario it is a value, and the wait happens when the run
does, not when the file is loaded:

```kotlin
import kotlin.time.Duration.Companion.seconds

val checkout = scenario("checkout") {
    exec(browse, api.get("/products"))
    pause(2.seconds)                     // a user reading the page
    exec(placeOrder, api.post("/orders"))
}
```

A pause records nothing: no row in the report and no percentile. Every number
printed under a step name is time the target took, and two seconds of somebody
reading is not. Counted as latency it would invent slowness the service never
caused; counted as a fast step it would flatter the tail of the real steps. It
is not lateness either, because the generator was always meant to wait before
that departure. What a pause does change is the concurrency a given rate
produces, and that is the reason to write one.

## Load shapes

A load shape is a sequence of stages, and it is a value as well, so it composes
and it answers before a request leaves:

```kotlin
val soak = rampRate(from = 0.perSecond, to = 200.perSecond, over = 1.minutes)
    .then(hold(200.perSecond, over = 10.minutes))
    .thenRampTo(0.perSecond, over = 1.minutes)

checkout.injecting(soak).profile.userCount()   // 132,000, before anything is sent
```

## Randomized arrivals

Evenly spaced departures are something no real target receives. Real session
arrivals are close to a Poisson process, and queueing delay grows with how
variable the arrivals are, not only with their average rate, so an evenly spaced
generator understates queueing at the very rate it claims to test. `randomized`
draws the arrival times instead of spacing them:

```kotlin
val bursty = soak.randomized(seed = 20260826)

bursty.userCount() shouldBe soak.userCount()   // the count is exact, the spacing moves
bursty.over shouldBe soak.over
```

The seed has no default, because an unseeded random run is not one anyone can
reproduce. Each second of the shape gets exactly the arrivals its rate line
calls for, placed where sorted uniform samples fall, so a ramp still ramps and
every departure lands inside the window the profile promised. Each stage is
seeded from the run seed and its own index, so a hold after a ramp does not
repeat the ramp's draws, and the whole shape stays a function of the one seed.

Even spacing is still the default. The report states which of the two a run
actually used, as a line rather than a warning, next to the spacing that was
produced:

```kotlin
val result = kestrel.run(checkout.injecting(bursty))

result.arrivals.count   // departures the run made
result.arrivals.mean    // 5.00ms between them
result.arrivals.cov     // 0.98 — near 1.0 is a Poisson process, 0.0 is a metronome
```

These three numbers are measured from the departures that went out, not read
back off the profile that asked for them, which is the check standard advice
tells you to make of your own generator. An evenly spaced run reports a
coefficient of variation of zero, and the report says what that costs: a p99
measured under even arrivals is optimistic against the same average rate in
production.

## Two clocks on every step

Every step reports two latencies. `serviceTime` is what the target took.
`responseTime` counts from the departure the profile promised, so a generator
that fell behind reports its own backlog rather than blaming a fast target. When
that backlog grows large enough to have moved a printed number,
`result.fellBehind()` is true, and every report says so before it prints a
percentile.

## Successes and failures, kept apart

A slow error is not a fast one, so each step splits both latencies by how the
request ended. A service shedding load answers a large share of requests with an
immediate rejection, and counted beside the successes those fast failures pull
the whole distribution down: the run reports a p99 nobody experienced, and the
better the target sheds load, the better the number looks.

```kotlin
result[placeOrder].serviceTime.p99               // every sample, unchanged
result[placeOrder].ok.serviceTime.p99           // the requests that worked
result[placeOrder].failed.serviceTime.p99       // the requests that did not
result[placeOrder].failed.count                 // 41
result[placeOrder].failed.reasons               // {HttpStatus(503): 41}
result[placeOrder].failedWith(HttpStatus(503))  // 41
```

A reason is a value, not a string. The module that made the request is the one
that knows what went wrong, so `kestrel-http` names a status, a rejected check,
and a capture that found nothing. Core names the three that belong to no
protocol: `TimedOut`, `Threw(className)`, and `Said(text)`, which is what
`fail("...")` records for a step body with no typed reason to give. Your own
step can declare its own reasons, and a report prints whatever each one is
`described` as.

Because reasons are values, you can ask them things a string cannot answer:

```kotlin
result[placeOrder].failed.reasons.keys
    .filterIsInstance<HttpStatus>()
    .filter { it.code >= 500 }
```

The whole-step timings are the two sides merged, so no number has moved. They
are what they were; the new part is that you can now ask which requests each one
describes. The HTML report prints the failed distribution against the successful
one whenever a step failed something, and prints nothing where nothing did.
Percentile goals still read the whole step: a p99 over successes alone can be
met by a target that failed most of the load, which is the mirror of the bug
this catches. `failureRate` is a count over a count, so it is unchanged.

## Trace IDs

None of those numbers can say where a slow request spent its time, because
Kestrel measures from outside the target. The join to whatever does know
(Jaeger, Tempo, Datadog) is a trace id, so a traced client puts one on every
request:

```kotlin
val api = http.baseUrl("https://orders.internal").traced()
```

Each request carries a W3C `traceparent` of the form
`00-<trace id>-<parent id>-00`, plus a `baggage` entry of `synthetic=true` so
the traffic can be told apart from real users downstream, before a shared
environment autoscales for it. The sampled flag is left off: whether to record a
trace is the target's decision, and a load generator forcing it would be
choosing the sampling policy of a system it does not own. Ids come from a
counter and a per-thread seed rather than `SecureRandom`, because a blocking
entropy source on the path being timed is a stall the report would charge to the
target.

## Checks

A status is not the only way an answer can be wrong. A target that degrades into
cheerful, empty 200s can look healthier than one that fails honestly. `checking`
asks a question of the response, and an answer that fails it fails the step under
the check's name:

```kotlin
api.post("/orders")
    .expecting(201)
    .checking("has an id") { response -> "\"id\"" in response.body }

result[placeOrder].failed.reasons   // {CheckFailed("has an id"): 41}
```

The name is required rather than derived, because a report that says `check
failed` for three different checks is one nobody can act on. A check reads the
whole body, which is held in memory to be read, so a request that streams
something large cannot also be checked.

## Watching the machine you send from

Every run also watches the machine it is sending from. A task due every
millisecond records how much later than that it actually ran, so a stall in the
measuring process shows up beside the tail it caused rather than hidden inside
it:

```kotlin
result.hiccups.p99   // 14ms, what the injector's own JVM stalled for
result.hiccups.max
```

The ticks run on an executor that no departure and no step is ever submitted to,
and their histogram is read only once that executor has terminated, so watching
the machine cannot move the numbers being watched. A tail no larger than
`hiccups.p99` could be this machine as readily as the target, and both reports
print the two together.

## Calibration: what the machine can resolve

Before a report compares two runs, it can ask what the machine can tell apart at
all. Calibration runs the ordinary step machinery against an action that does
nothing (no socket, no target) and reports how far apart repeats of that one
unchanging thing landed:

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
        floor.probe            // 50us — what the probe took here, for another machine
    }
}
```

`resolution` is measured at the median, where the statistic is the machine's own
throughput and a fraction of it still means something at another scale. It
bounds the size of a change. A claim about a tail also has to clear `hiccups.p99`
in absolute terms, which is the other half of what one calibration measures.
`probe` answers a third question from the same repeats: the magnitude they were
measured at, which is what a baseline from another machine can be compared
against.

The floor is a property of the machine rather than of a run, so it is measured
once per JVM and kept, capped at thirty seconds. On a runner you have already
characterised, `-Dkestrel.resolution=0.02` supplies the value instead of
measuring it again. Hand the floor to a report and the page says what it can
resolve; where the floor is too large for any latency claim to rest on, the page
says so where the comparison would have gone, rather than printing one nobody
should act on:

```kotlin
import io.github.matthewjones372.kestrel.against
import io.github.matthewjones372.kestrel.report.writeHtmlReport
import java.nio.file.Path

result.writeHtmlReport(Path.of("build/reports/kestrel.html"), result.against(baseline), floor)
```

The null step is what makes that number mean something. Reading the floor off
the run's own variance instead would fold the target's variability into it, and
a genuinely erratic target would raise its own noise floor and hide its own
regressions.

## Percentiles and tails

A timing carries the buckets it was read from, so it can answer a percentile
nobody asked for during the run. `p999` is among them, and it is where two JVM
collectors that match at p99 come apart:

```kotlin
import io.github.matthewjones372.kestrel.Tail

result[placeOrder].serviceTime.percentile(99.95)      // any percentile, off the buckets

when (val tail = result[placeOrder].serviceTime.p999) {
    is Tail.Measured -> tail.duration
    is Tail.Absent -> tail.because   // "only 400 samples, and under 1000 …"
}
```

A run of four hundred requests has not measured one request in a thousand, so
`p999` answers with the reason rather than a number nobody measured. A goal can
name the tail just as easily (`p999(placeOrder) under 1.seconds`, alongside
`p50`, `p95` and `p99`), and a run too short to have measured that tail misses
the goal carrying the same reason, rather than passing on a percentile it never
reached. The HTML report prints each step's tail with the 95% sampling interval
around it, which is the width you should expect from a number resting on one
request in a thousand.

## Capacity search

You rarely pick a rate because you want to know about that rate. The question
you actually have is usually "what can this take?" To ask it, hand a search the
ceiling you consent to and what good looks like along the way:

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

The search climbs a coarse ladder, then bisects between the last rung that
passed and the first that did not, so the resolution goes where the knee is and
nothing is spent on the flat left-hand side. It carries on two rungs past the
first failure, because the shape past the knee is what says whether the target
sheds load or collapses.

A rung where the injector lost ground on its own rate is **void** rather than
failed. A p99 lateness above one whole departure interval means the generator
was a departure behind at the tail, so the load was never offered and nothing
was learned about the target. The search stops there rather than publish the
generator's own ceiling under the target's name. `capacity.voided` says that
happened, `rung.offered` says how much load actually left against what the
profile promised, and `capacity.rate` is then a floor the generator reached
rather than a ceiling the target could not pass.

That gate is only about the schedule. `result.fellBehind()` asks a different
question, whether the backlog is large enough to have moved a printed number,
and a rung judged on that one is void whenever the target is fast, however well
the generator kept time.

`capacity.toHtmlReport()` puts the whole curve on one self-contained page: every
rung, the verdict it earned, and the operating point marked on both the chart
and the table.

## Response-time targets and goodput

The same buckets answer another promise a team makes to its users: what share of
requests came back inside the target at all.

```kotlin
import io.github.matthewjones372.kestrel.Met

when (val met = result[placeOrder].responseTime.share(under = 200.milliseconds)) {
    is Met.Measured -> met.fraction   // 0.994
    is Met.Absent -> met.because      // "nothing was recorded, …"
}
```

The bucket the target falls inside counts as a miss, in the direction every
percentile here already rounds: a share is never larger than the share that
really met the target, so it is a number you can quote. A step that recorded
nothing says so, rather than reporting a zero someone reads as a service that
met nothing.

A share of requests and a rate of them are two halves of the same promise, so
`goodput` answers both: the requests that both succeeded and came back inside the
target, and what that comes to per second.

```kotlin
import io.github.matthewjones372.kestrel.goodput
import io.github.matthewjones372.kestrel.percent
import kotlin.time.Duration.Companion.milliseconds

result[pay].met(under = 200.milliseconds)     // Met.Measured(0.98)
result.goodput(under = 200.milliseconds)      // 4,973/s across every step

checkout.at(5_000.perSecond, over = 2.minutes)
    .expecting(goodput(pay, under = 200.milliseconds) atLeast 99.percent)
```

The rate is over the window the plan asked for, not the span the run happened to
take, which is what makes two runs comparable; a run that did not keep to its
window is already saying so through `fellBehind()`. Goodput is read off the
successes' own distribution over every request the step made, so a failure that
was also slow is counted out once rather than twice. A user abandoned after a
failed step counts against the step that failed, and not again against the steps
it never reached.

## Asynchronous completions

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
never minus the publish. Measured from ingestion, a pipeline's latency stays
flat while the system falls apart, which is why it is the number every dashboard
shows. The publish is timed too, under the emit step, because a slow producer
and a slow pipeline are different problems, and adding them together hides both.

A record that never arrives is the finding rather than a missing sample, so it is
counted rather than dropped, and counted apart from one the run simply did not
wait for. `drainingFor` is required and has no default: it is the line between
those two cases, and choosing it for you would move records across that line
without saying so. `result.fellBehind()` stays what it always was, the
injector's own backlog; the pipeline falling behind is what the latency above
measures.

## Watching a run

A run says what it is doing while it does it, so a ten-minute soak is not ten
minutes of silence somebody kills:

```
kestrel: 00:05  departed 25,000  in flight 312  behind 138.797us
kestrel: 00:10  departed 50,000  in flight 298  behind 1.212604ms
```

```kotlin
import io.github.matthewjones372.kestrel.Progress
import kotlin.time.Duration.Companion.seconds

simulation.run(Progress.lines(every = 30.seconds))   // less often
simulation.run(Progress.silent)                      // not at all
```

Every number on the line is one the scheduler already keeps: how many users it
has sent, how many are still running, and how late the last one left. Request
counts, failures and percentiles are deliberately absent. Reading a live
histogram, or merging the recorders mid-run, would put the watching inside the
thing being measured, and a generator that moves what it measures reports its
own weight as the target's latency. The frozen `RunResult` stays the only place
a number is quoted from.

`Progress` has a single method, so a caller who wants a logger, a metrics sink,
or a line of their own writes `Progress { elapsed, snapshot -> ... }` and passes
it in.

## Tracing one user

A scenario that is wrong reports `status 404` and stops there. `trace` walks one
user through it and prints what each step did, so the authoring loop is not a
`println` inside a step body that the next run times as part of that step:

```kotlin
import io.github.matthewjones372.kestrel.engine.trace
import io.github.matthewjones372.kestrel.feed
import io.github.matthewjones372.kestrel.sessionKey

val email = sessionKey<String>("email")

checkout.trace(feed(email) { user -> "user$user@example.com" })
```

```
kestrel: trace checkout
kestrel:   browse       ok
kestrel:   place order  ok
kestrel:   pay          FAILED status 503 — user abandoned here
```

One user, one pass, no rate and no duration. `trace` walks the same steps a run
walks, so it cannot disagree with the run it exists to explain, and a step that
fails abandons the user right there, exactly as it does under load. The feeder is
optional; give one and the user starts with the data a real one would have.

A trace is for reading, not measuring. It schedules nothing and returns nothing.
Every number a single pass on a cold JVM could report would describe a cold JVM
sending one request, and a `RunResult` handed back here would reach
`writeHtmlReport` looking like one from a run that actually measured something.
Inside a test, `kestrel.trace(checkout)` is the same call, and it leaves the
runner's summary alone.

## Baselines

`kestrel-baseline` keeps a run in a file so the next one can be compared against
it. The file carries the buckets rather than five percentiles (an interval
cannot be rebuilt from those), along with the plan, the machine, and what a
calibration probe took on that machine:

```kotlin
import io.github.matthewjones372.kestrel.Change
import io.github.matthewjones372.kestrel.Comparison
import io.github.matthewjones372.kestrel.against
import io.github.matthewjones372.kestrel.baseline.readBaseline
import io.github.matthewjones372.kestrel.baseline.writeBaseline
import io.github.matthewjones372.kestrel.calibratedBy
import java.nio.file.Files
import java.nio.file.Path

val baseline = Path.of("build/kestrel/checkout.kestrel")

// What the probe took here travels with the run, so the next comparison can
// ask whether the runner changed under it.
val measured = result.calibratedBy(kestrel.calibrate())
val previous = baseline.takeIf { Files.exists(it) }?.let(::readBaseline)

when (val comparison = measured.against(previous)) {
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

measured.writeBaseline(baseline)
```

A run of a different plan is not compared at all. `NotComparable` names what
differs (the scenario, its steps, or its rate line), because comparing a smoke
run to a soak breaks every interval and bucket underneath it, and no statistics
can rescue it. Having no baseline at all is also `NotComparable`, rather than a
comparison quietly left off the page: an empty page reads the same whether this
was a first run or a cache key broke, and those need different fixes. A run on a
different machine is compared, with `caveat` naming the two runners and warning
that any delta may be one of them. A team whose runners are all shared would
otherwise never get a comparison at all, and they should get one, with the
caveat attached.

The caveat names a measured runner before a described one. Two hosted runners of
the same specification are the same `Machine` but not the same speed, so a
baseline carries what the calibration probe took on the machine that wrote it,
and a later run compares that against its own:

```kotlin
comparison.slowdown   // 2.0 — this runner ran the same probe in twice the time
```

Two probe timings are the same target-free work measured twice, which is the one
cross-machine comparison that is genuinely like for like. It is not `resolution`:
that is the spread of those repeats as a fraction of a null step's own tiny
median, so it is a fraction of a different number on every machine. Once a runner
is more than a quarter slower, the caveat leads with the runner, above any step,
because the runner is then the likelier explanation for everything under it.

The file has carried the probe since version 4. A version 3 baseline still reads,
with no probe and so no claim about the runner. A version this build does not
recognise is refused by name rather than half-read.

Hand the comparison to the report and the page carries it, including a refusal,
which is the difference between a first run and a cache key that broke:

```kotlin
import io.github.matthewjones372.kestrel.report.writeHtmlReport

result.writeHtmlReport(Path.of("build/reports/kestrel/checkout.html"), comparison)
```

The same comparison goes into a GitHub job summary, where a pull request reads
it:

```kotlin
import io.github.matthewjones372.kestrel.report.appendToStepSummary

result.appendToStepSummary(comparison, floor)
```

Where to keep the file on GitHub (a cache key, an artifact, or a branch somebody
reviews), and why latency should not gate a merge on a shared runner, are in
[docs/cookbook.md](docs/cookbook.md), along with publishing the reports to Pages
and the rest of the recipes.

## Running more than once

On the JVM the process is the unit of replication. JIT profile, code cache and
heap layout differ between invocations, and the same benchmark on the same VM
reaches steady state in some processes and not others, so a single run
attributes all of that to the code. `Runs` holds several and answers as one:

```kotlin
import io.github.matthewjones372.kestrel.Runs

val runs = Runs(listOf(first, second, third))

runs.size                                   // 3
runs.merged[pay].serviceTime.p99            // the percentile of all three runs' samples
runs.merged.timeline[0].count               // the first second of all three, superimposed
runs.each.map { it[pay].serviceTime.p99 }   // and the three it was merged from
```

You get both, because they answer different questions. `merged` adds the buckets
and reads the percentile off the sum, which is the only honest way to combine two
histograms: a percentile of the whole population cannot be recovered from the
percentiles of the parts, and an average of ten p99s is not the p99 of anything.
There is no API here that takes the second route. `each` keeps the runs apart,
which is what an interval across processes is made of.

A merge is refused where a comparison would only warn. Runs of different plans
are refused, naming what differs, the way `NotComparable` does. So are runs
measured on different machines, which `against` compares with a caveat instead.
So is a run carrying a timeline merged with one carrying none, because padding an
absent timeline would invent seconds nobody has the data for. A comparison keeps
two populations apart; a merge would pool them.

The merged timeline is second n of every run superimposed, not one run after
another. Ten two-minute runs give two minutes of ten times the load, so ten
processes warming up land in the same early seconds rather than being smeared
across the whole run. That is the experiment the merged percentiles beside it
already describe. The cost is dilution: a run slow only in its own third second
is pooled with nine that were not. That is the same trade the merged percentiles
make, and `each` still holds every run on its own.

A run that stopped a second short of the longest is padded with the zero seconds
it recorded rather than refused. Timeline length is wherever the last response
landed, so two honest replications of the same two-minute plan routinely freeze
at 119 and 120 seconds. A zero there is the same measurement as the interior
zeros a single run already writes, now that the merge gives the run an end a
single one does not have. A run read back from a baseline file has no timeline at
all, because the format does not carry one, so runs read from a directory merge
to a result with none either.

The first run is kept rather than dropped. The classic protocol discards the
first invocation as the cold one, and `runs.first` and `runs.afterFirst` name
both halves, so discarding it is a caller's decision on the record rather than
data this tool quietly threw away.

Spend repetitions where the variance is. For JVM work that is between processes
rather than inside them, which argues for more short runs over fewer long ones:
ten two-second runs say more about the spread than one twenty-second run.

Ten runs is one `main` and a shell for-loop. There is no forking launcher here
and CI does not need one. The one thing a shell cannot do is write a file per
invocation without overwriting the last, and that is `writeInto`, which names
each file for when the run started and which process measured it.

```kotlin
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.baseline.writeInto
import io.github.matthewjones372.kestrel.engine.Kestrel
import io.github.matthewjones372.kestrel.http.http
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.step
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

val pay = step("pay")

fun main() {
    val api = http.baseUrl("http://localhost:8080")
    val paying = scenario("paying") { exec(pay, api.get("/pay")) }

    Kestrel().run(paying.at(50.perSecond, over = 2.seconds)).writeInto(Path.of("build/kestrel"))
}
```

```bash
for i in $(seq 1 10); do java -cp "$classpath" com.example.PayingKt; done
```

```kotlin
import io.github.matthewjones372.kestrel.Runs
import io.github.matthewjones372.kestrel.baseline.readAll
import java.nio.file.Path

val runs = Runs.readAll(Path.of("build/kestrel"))   // ten files, oldest first
```

`readAll` takes a directory rather than a list of paths, so nothing has to agree
on the names, and it leaves anything in there that is not a run alone.

## Comparing two sets of runs

Two sets of runs and one statistic make a statement a single pair cannot: not
"302 against 290", but how far apart they are and how much of that the runs will
support.

```kotlin
import io.github.matthewjones372.kestrel.Tell
import io.github.matthewjones372.kestrel.against
import io.github.matthewjones372.kestrel.p99
import io.github.matthewjones372.kestrel.percent
import io.github.matthewjones372.kestrel.step

val pay = step("pay")

val difference = candidate.against(baseline, p99(pay), acceptable = 3.percent)

difference.ratio      // 1.04 — four percent slower
difference.interval   // Spread(low=1.015, high=1.065)

when (val verdict = difference.verdict) {
    Tell.Worse, Tell.Better -> println("${difference.statistic.described} moved by ${difference.ratio}")
    is Tell.CannotTell -> println("${verdict.why}. ${verdict.wouldChangeIt}.")
}
```

The ratio is read off each side's merged population, because that is where a
percentile of a population comes from. The interval around it is a bootstrap over
`each`: ten thousand resamples of the runs themselves, seeded from a constant so
that reading one set of results twice reaches one verdict. A parametric interval
would assume a shape that latency does not have.

The verdict is that interval against a threshold the caller declares, rather than
against zero. A team that cares about 1% and a team that cares about 10% are
asking different questions of the same data. An interval entirely past the
threshold is `Worse` or `Better`; one that spans it, or sits entirely inside it,
is `CannotTell`. Every `CannotTell` carries `wouldChangeIt`, because a refusal
nobody can act on is one a team learns to route around.

Fewer than five runs a side is refused rather than answered. A bootstrap over
three values is arithmetic wearing a lab coat.

Any statistic a goal can name is one a comparison can read, and it knows which
way is bad: a slower percentile is worse, and less goodput is worse.

```kotlin
import io.github.matthewjones372.kestrel.goodput
import kotlin.time.Duration.Companion.milliseconds

candidate.against(baseline, goodput(pay, under = 200.milliseconds))
```

Hand a list of them to the report and the page carries each one: the size, the
interval, the verdict, and for a verdict that cannot tell, what would change it.

```kotlin
import io.github.matthewjones372.kestrel.p95
import io.github.matthewjones372.kestrel.report.writeHtmlReport
import java.nio.file.Path

candidate.merged.writeHtmlReport(
    path = Path.of("build/reports/kestrel.html"),
    differences = listOf(
        candidate.against(baseline, p99(pay), acceptable = 3.percent),
        candidate.against(baseline, p95(pay), acceptable = 3.percent),
    ),
)
```

The assertion is in both test frameworks, and the threshold is declared at the
assertion rather than at the comparison, because that is where somebody decided
what mattered:

```kotlin
import io.github.matthewjones372.kestrel.kotest.NotWorseThan

candidate.against(baseline, p99(pay)) shouldBe NotWorseThan(3.percent)
```

```kotlin
import io.github.matthewjones372.kestrel.junit5.assertNotWorseThan

candidate.against(baseline, p99(pay)).assertNotWorseThan(3.percent)
```

A comparison that cannot tell passes both. A test that failed on "cannot tell"
would fail on a noisy Tuesday and get deleted on the Wednesday, so stopping for
one is `orCannotTell = true`, asked for by name.

Hand the comparison the machine's floor and it consults both halves of it before
concluding anything:

```kotlin
import io.github.matthewjones372.kestrel.Floor

candidate.against(baseline, p99(pay), acceptable = 3.percent, floor = kestrel.calibrate())
```

`hiccups` is absolute, and absolute noise transfers between magnitudes: a p99
that moved by less than the injector's own stalls moved because of the injector,
and every claim read in durations has to clear the stalls at its own percentile.
A stall one request in a hundred waits for moves a p99 and leaves a median where
it was.

`resolution` is a fraction of what the null step measured, and a fraction does
not transfer. A null step whose median moves from 50 µs to 110 µs reports 120%,
while a target at 250 ms on the same machine in the same second moved by the same
60 µs, a fifth of a percent. So the relative bound is applied while it is still a
bound a claim could clear; above that it is a statement about the magnitude it
was taken at rather than about the comparison, and the absolute gate carries the
refusal on its own.

Either way the refusal names the number it failed and what would change it: a
quieter machine, or a difference larger than the one the machine makes by itself.

## The timeline

A whole-run p99 cannot tell a target that degraded after ninety seconds from one
that was evenly slow: both report the same number. `result.timeline` is the run
second by second, counted from its start:

```kotlin
import io.github.matthewjones372.kestrel.Histogram

result.timeline.size                  // seconds the run covered
result.timeline[0].count              // requests that left in the first second
result.timeline[0].failed             // how many of those failed
result.timeline[0].p99                // the target's service time that second
result.timeline[0].serviceTime        // the buckets that came from, to add up
result.timeline[0].okServiceTime      // and the two sides, as a step splits them
result[placeOrder].timeline           // the same, for one step

Histogram.COARSE_PRECISION            // 0.0625 — what a second's percentile is good to
```

A second nothing ran in is present and zero rather than missing, because a gap in
a line is information and a dropped point is a lie about the shape. The
percentiles come from a coarse histogram (6.25% rather than the summary's 0.78%)
because a full table per second per step would be tens of megabytes of counters
for a ten-minute run, and a generator competing with its target for memory
measures itself. Quote the summary for a number and the timeline for a shape;
[docs/what-it-costs.md](docs/what-it-costs.md) has the arithmetic.

The HTML report draws all three over the run (requests a second, p50 and p99 a
second, and failures a second) as inline SVG, each second a flat segment because
nothing was measured between two of them, and that precision printed underneath.

## Steady state

The first seconds of a run on the JVM are class loading, compilation and a cold
connection pool, so a whole-run p99 is partly a measurement of starting up. The
usual fix is a warm-up setting, but the evidence is that the setting is a guess,
wrong by a median of 28 seconds even when the benchmark's own author wrote it. So
the segment is found in the timeline instead:

```kotlin
import io.github.matthewjones372.kestrel.SteadyState
import io.github.matthewjones372.kestrel.steadyState

when (val settled = result.steadyState) {
    is SteadyState.From -> settled.offset          // 20s — the run held from here on
    is SteadyState.NeverSettled -> settled.why     // in the words the report prints
}

SteadyState.TOLERANCE                              // 0.125 — how far two windows may differ
SteadyState.LEAST_INTERVALS                        // 10 — under this there is nothing to detect
```

The run is cut into four windows, and the detector looks for the earliest second
after which no later window is materially better than the last one and the tail
holds within `TOLERANCE` of its own mean. A run that was still getting faster at
the end, and one that got slower and stayed slower, both report `NeverSettled`,
with different reasons, because they are different findings. Neither fails the
run: a tool that failed a build on a detector's opinion is a tool whose detector
gets turned off.

`TOLERANCE` is twice `Histogram.COARSE_PRECISION` and is written as a multiple of
it. Two readings of one unchanged latency can land a bucket apart, so a tolerance
at the width of that bucket would be a detector chasing which bucket a sample fell
in. It is a default rather than something the run discovered, which is why the
report prints it beside the verdict.

`result.steady` is the same run over that segment, and the whole run where there
is no segment. Nothing is discarded: every number above stays where it was, and
what was left out is reported.

```kotlin
import io.github.matthewjones372.kestrel.steady

result[placeOrder].serviceTime.p99           // the whole run, cold start included
result.steady[placeOrder].serviceTime.p99    // the same p99 without the first 20 s
result.steady[placeOrder].responseTime.p99   // and the clock a goal reads by default
result.steady.count                          // requests that left inside the segment
result.steady.startedAt                      // the run's start plus the offset
```

Goals are judged over the segment where the timeline measured what they read: the
counts, which are exact, and both clocks. The generator's own backlog is not kept
second by second, so a goal on that is judged over the whole run rather than
narrowed to a segment nothing measured. `Goal.overSteadySegment` says which, and
the page says so above the numbers. A run that never settled has all of its goals
judged over all of it.

## Why Kestrel exists

Gatling is the reference point. Kestrel exists because of what using Gatling from
Kotlin is like, not because anything is wrong with Gatling itself. Gatling has
had a Kotlin DSL for years, but that DSL is a Kotlin surface over a Java one, and
what Kotlin would otherwise buy does not survive the trip. In that DSL:

- A session key is a string, and its type is named where the value is read
  rather than where the key is declared. You write `getString("orderId")` at
  each use and trust it to agree with the write by convention.
- A step is named by a string at the `exec` and by that same string again
  wherever you assert its result, so a rename leaves a report with a row nobody
  reads instead of failing to compile.
- A path carries `#{orderId}`, resolved against the session while the run is
  going, so a typo becomes a failed request at minute six.
- A simulation is a class extending `Simulation` with its `setUp` in an
  initialiser, so there is nothing to hold: no value to compose, none to print,
  and nothing to ask `userCount()` of before anything is sent.
- The run goes through its own plugin and its own launcher, with the assertions
  written inside the simulation in Gatling's language, so what comes back is a
  report directory rather than a number a test can read.

None of these is a defect. They are what a DSL designed for Scala and adapted
twice looks like from Kotlin. Together they add up to a scenario the compiler
cannot check and a result the test framework cannot see. The first code block in
this README is the same test with each of them removed.

The other half of Kestrel is not about ergonomics. A generator that falls behind
its own schedule reports its backlog as the target's latency, and most tools in
this class cannot tell you whether that happened. Everything above this section
is that argument: two clocks on every step, the injector's own stalls measured
beside the tail they get blamed for, a floor the machine is asked for before a
comparison is drawn, and a verdict allowed to say it cannot tell.

Three decisions shape the rest, and all three are still open:

- **What a scenario is.** A value, not a builder that runs as it is called, so it
  can be composed, filtered, printed and compared before anything is sent.
- **What runs it.** Whatever engine the first spec argues for, behind an
  interface that core declares, so the description does not belong to the engine.
- **What honest numbers mean here.** Coordinated omission is the default bug in
  this class of tool. Where the design admits it, Kestrel says so out loud rather
  than smoothing it over.

## Building

```bash
./gradlew build          # tests, detekt, spotless, coverage
./gradlew spotlessApply  # run this last, before you commit
```

`smoke/` is a separate Gradle build that depends on the published coordinates
rather than on the projects, so a wrong POM or a missing jar fails at resolution
here instead of being found by a stranger:

```bash
./gradlew publishToMavenLocal
cd smoke && ../gradlew test
```

It resolves `0.1.0-SNAPSHOT` by default; `-PkestrelVersion=0.1.0` points the same
test at a release.

## Module layout

| Module | Depends on | For |
|---|---|---|
| `kestrel-core` | **nothing** | scenarios, profiles and results as values, and the `Engine` that runs one |
| `kestrel-engine` | core | `VirtualThreads`: departures on a schedule, a user to a thread |
| `kestrel-http` | core | HTTP steps on `java.net.http` |
| `kestrel-websocket` | core | WebSocket handshakes on `java.net.http`, a connection per user |
| `kestrel-junit5` | core, engine, JUnit | a load test that is a `@Test` |
| `kestrel-kotest` | core, engine | the same, in a Kotest spec |
| `kestrel-baseline` | core | a run kept in a file, to compare the next one to |
| `kestrel-report-html` | core | one self-contained, interactive HTML file |
| `kestrel-report-github` | core | markdown, a job summary, a Pages index |
| `kestrel-pelican` | core, `pelican-core` | [Pelican](https://github.com/matthewjones372/pelican) endpoints as steps |

Every row is a test, not a promise: each module asserts its own runtime
classpath, so core cannot grow a dependency and the Kotest module cannot quietly
start needing the JUnit one.

Core depends on the Kotlin standard library and nothing else, and a test says so.
Anything with a third-party type in it becomes a leaf module beside core.

[docs/modules.md](docs/modules.md) names the test behind each row, and the
coordinates to depend on them.

## License

Apache 2.0 — see [LICENSE](LICENSE).
