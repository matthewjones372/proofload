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

A step splits both of those by how the request ended, because a slow error is
not a fast one. A service shedding load answers a large share of requests with
an immediate rejection, and counted beside the successes those fast failures
pull the whole distribution down: the run reports a p99 nobody experienced, and
the better the shedding the better the number looks.

```kotlin
result[placeOrder].serviceTime.p99          // every sample, unchanged
result[placeOrder].ok.serviceTime.p99       // the requests that worked
result[placeOrder].failed.serviceTime.p99   // the requests that did not
result[placeOrder].failed.count             // 41
result[placeOrder].failed.reasons           // {"status 503": 41}
result[placeOrder].failedWith("status 503") // 41
```

The whole-step timings are the merge of the two sides, so no number has moved:
they are what they were, with the question of which requests they describe now
answerable. The HTML report prints the failed distribution against the
successful one whenever a step failed something, and prints nothing where
nothing did. Percentile goals still read the whole step — a p99 over successes
alone can be met by a target that failed most of the load, which is the mirror
of the bug this catches — and `failureRate` is a count over a count, so it is
unchanged.

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
import kotlin.time.Duration.Companion.milliseconds

class ResolutionTest {

    @LoadTest
    fun `what this machine can tell apart`(kestrel: Kestrel) {
        val floor: Floor = kestrel.calibrate()

        floor.absolute         // 61us — how far a repeat of one measurement moved here
        floor.resolution       // 1.25 — the same, as a fraction of what it was read off
        floor.hiccups.p99      // 14ms — what the injector itself stalled for
        floor.probe            // 49us — what the probe took here, for another machine

        // true: 4% of 250 ms is 10 ms, which is well past 61us
        floor.resolves(0.04, of = 250.milliseconds)
    }
}
```

`absolute` is the one that transfers. A null step's median is tens of
microseconds, so on a busy machine `resolution` reads over 100% — and a 250 ms
target measured in the same second did not move by 100%, it moved by the same
handful of microseconds. So a claim is judged in duration, against the magnitude
it is being made at, and `resolution` is kept for reading it back at the scale
it was taken at. `absolute` is recovered from the calibration that already ran
rather than measured a second time: it is the spread `resolution` divides away.

Neither of them watched a target. Both bound the measuring machinery — how far
its own repeats landed apart, and what its JVM lost to the rest of the machine —
so a target's own run-to-run drift is outside both, and bounding *that* takes
repeats of the target rather than of a null step.

The floor is a property of the machine rather than of a run, so it is measured
once per JVM and kept, bounded at thirty seconds. On a runner somebody has
already characterised, `-Dkestrel.resolution=0.02` names it instead of measuring
it again — a declared floor is a fraction with no magnitude behind it, so it is
applied to whatever it is read off rather than re-based. Hand a floor to a
report and the page says what the machine moved by and what fraction of the
measurement that came to:

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

A rung where the injector lost ground on its own rate is **void** rather than
failed: a p99 lateness above one whole departure interval means the generator
was a departure behind at the tail, so the load was never offered and nothing
was learned about the target. The search stops there rather than publish the
generator's own ceiling under the target's name. `capacity.voided` says that
happened, `rung.offered` says how much load actually left against what the
profile promised, and `capacity.rate` is then a floor the generator reached
rather than a ceiling the target could not pass.

That gate asks about the schedule and nothing else. `result.fellBehind()` asks
a different question — whether the backlog is large enough to have moved a
number the page prints — and a rung judged on that one is void whenever the
target is fast, however well the generator kept time.

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
window is already saying so through `fellBehind()`. It is read off the
successes' own distribution over every request the step made, so a failure that
was also slow is counted out once rather than twice. A user abandoned after a
failed step counts against the step that failed, and not again against the
steps it never reached.

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
be rebuilt from those — and the plan, the machine and what a calibration probe
took on it:

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

A run of a different plan is not compared at all: `NotComparable` names what
differs — the scenario, its steps or its rate line — because comparing a smoke
run to a soak undoes every interval and bucket underneath it, and no statistics
rescue it. No baseline at all is `NotComparable` too, rather than a comparison
quietly left off: a page with nothing on it reads the same whether this was a
first run or a cache key broke, and those want different fixes. A run on a
different machine *is* compared, with `caveat` naming the two runners and
saying every delta may be one of them: a team whose runners are all shared
would otherwise never get a comparison at all, and they should get one with the
caveat attached.

The caveat names a *measured* runner before a described one. Two hosted runners
of the same specification are the same `Machine` and not the same speed, so a
baseline carries what the calibration probe took on the machine that wrote it
and a later run compares that against its own:

```kotlin
comparison.slowdown   // 2.0 — this runner ran the same probe in twice the time
```

Two probe timings are the same target-free work measured twice, which is the
one comparison across machines that is like for like. It is not `resolution`:
that is the *spread* of those repeats as a fraction of a null step's own tiny
median, so it is a fraction of a different number on every machine. Past a
quarter slower the caveat leads with the runner, above any step, because the
runner is then the likelier explanation of everything under it.

The file carries the probe from version 4 on. A version 3 baseline still reads,
with no probe and so no claim about the runner; a version this build does not
know is refused by name rather than half-read.

Hand the comparison to the report and the page carries it — including a refusal,
which is the difference between a first run and a cache key that broke:

```kotlin
import io.github.matthewjones372.kestrel.report.writeHtmlReport

result.writeHtmlReport(Path.of("build/reports/kestrel/checkout.html"), comparison)
```

The same comparison goes in a GitHub job summary, where a pull request reads it:

```kotlin
import io.github.matthewjones372.kestrel.report.appendToStepSummary

result.appendToStepSummary(comparison, floor)
```

Where to keep the file on GitHub — a cache key, an artifact or a branch
somebody reviews — and why latency should not gate a merge on a shared runner,
are in [docs/cookbook.md](docs/cookbook.md).

## More than one run

On the JVM the process is the unit of replication. JIT profile, code cache and
heap layout differ between invocations, and the same benchmark on the same VM
reaches a steady state in some processes and not others, so a single run
attributes all of that to the code. `Runs` holds several and answers as one:

```kotlin
import io.github.matthewjones372.kestrel.Runs

val runs = Runs(listOf(first, second, third))

runs.size                                   // 3
runs.merged[pay].serviceTime.p99            // the percentile of all three runs' samples
runs.merged.timeline[0].count               // the first second of all three, superimposed
runs.each.map { it[pay].serviceTime.p99 }   // and the three it was merged from
```

Both, because they answer different questions. `merged` adds the buckets and
reads the percentile off the sum, which is the only honest way to combine two
histograms: a percentile of the whole population cannot be recovered from the
percentiles of the parts, and an average of ten p99s is not a p99 of anything.
There is no API here that takes the second route. `each` keeps the runs apart,
which is what an interval across processes is made of.

A merge is refused where a comparison would only warn. Runs of different plans
are refused, naming what differs, the way `NotComparable` does — and so are runs
measured on different machines, which `against` compares with a caveat instead,
and so is a run carrying a timeline merged with one carrying none, because
padding an absent timeline would invent seconds nobody has the data for. A
comparison keeps two populations apart; a merge would pool them.

The merged timeline is second *n* of every run superimposed, not one run after
another: ten two-minute runs give two minutes of ten times the load, so ten
processes warming up land in the same early seconds rather than being smeared
across the whole thing. That is the experiment the merged percentiles beside it
already describe. The cost is dilution — a run slow only in its own third second
is pooled with nine that were not — which is the trade those percentiles make
too, and `each` still holds every run on its own.

A run that stopped a second short of the longest is padded with the zero
seconds it recorded rather than refused. Timeline length is wherever the last
response landed, so two honest replications of the same two-minute plan
routinely freeze at 119 and 120 seconds; and a zero there is the same
measurement as the interior zeros a single run already writes, now that the
merge gives the run an end a single one does not have. A run read back from a
baseline file has no timeline at all, because the format does not carry one, so
runs read from a directory merge to a result with none either.

The first run is kept rather than dropped. The classic protocol discards the
first invocation as the cold one, and `runs.first` and `runs.afterFirst` name
both halves, so discarding it is a caller's decision on the record rather than
data this tool quietly threw away.

Spend repetitions where the variance is. For JVM work that is between processes
rather than inside them, which argues for more short runs over fewer long ones:
ten two-second runs say more about the spread than one twenty-second run.

Ten runs is one `main` and a shell for-loop. There is no forking launcher here
and CI does not need one: the part a shell cannot do is a file per invocation that does
not overwrite the last, and that is `writeInto`, which names each file for when
the run started and which process measured it.

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

## Better, worse, or cannot tell

Two sets of runs and one statistic make the statement a single pair cannot: not
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
percentile of a population comes from. The interval around it is a bootstrap
over `each` — ten thousand resamples of the runs themselves, seeded from a
constant so that reading one set of results twice reaches one verdict. A
parametric interval would be assuming a shape that latency does not have.

The verdict is that interval against a threshold the caller declares, rather
than against zero: a team that cares about 1% and a team that cares about 10%
are asking different questions of the same data. An interval entirely past the
threshold is `Worse` or `Better`; one that spans it, or sits entirely inside it,
is `CannotTell` — and every `CannotTell` carries `wouldChangeIt`, because a
refusal nobody can act on is one a team learns to route around.

Fewer than five runs a side is refused rather than answered. A bootstrap over
three values is arithmetic wearing a lab coat.

Any statistic a goal can name is one a comparison can read, and it knows which
way is bad — a slower percentile is worse, and less goodput is worse:

```kotlin
import io.github.matthewjones372.kestrel.goodput
import kotlin.time.Duration.Companion.milliseconds

candidate.against(baseline, goodput(pay, under = 200.milliseconds))
```

Hand a list of them to the report and the page carries each one — the size, the
interval, the verdict, and for a verdict that cannot tell, what would change it:

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

A comparison that cannot tell passes both. A test that fails on "cannot tell"
fails on a noisy Tuesday and gets deleted on the Wednesday — so stopping for one
is `orCannotTell = true`, asked for by name.

Hand the comparison the machine's floor and it consults both halves of it
before it concludes anything:

```kotlin
import io.github.matthewjones372.kestrel.Floor

candidate.against(baseline, p99(pay), acceptable = 3.percent, floor = kestrel.calibrate())
```

`hiccups` is absolute, and absolute noise transfers between magnitudes: a p99
that moved by less than the injector's own stalls moved because of the
injector, and every claim read in durations has to clear the stalls at its own
percentile — a stall one request in a hundred waits for moves a p99 and leaves
a median where it was.

`resolution` is a fraction of what the null step measured, and a fraction does
not transfer. A null step whose median moves from 50 µs to 110 µs reports 120%,
while a target at 250 ms on the same machine in the same second moved by the
same 60 µs — a fifth of a percent. So the relative bound is applied while it is
still a bound a claim could clear, and above that it is a statement about the
magnitude it was taken at rather than about the comparison, and the absolute
gate carries the refusal on its own.

Either way the refusal names the number it failed and what would change it: a
quieter machine, or a difference larger than the one the machine makes by
itself.

A whole-run p99 cannot tell a target that degraded after ninety seconds from
one that was evenly slow: both report the same number. `result.timeline` is the
run second by second, counted from its start:

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

A second nothing ran in is present and zero rather than missing, because a gap
in a line is information and a dropped point is a lie about the shape. The
percentiles come from a coarse histogram — 6.25% rather than the summary's
0.78% — because a full table per second per step is tens of megabytes of
counters for a ten-minute run, and a generator competing with its target for
memory measures itself. Quote the summary for a number and the timeline for a
shape; [docs/what-it-costs.md](docs/what-it-costs.md) has the arithmetic.

The HTML report draws all three over the run — requests a second, p50 and p99
a second, and failures a second — as inline SVG, each second a flat segment
because nothing was measured between two of them, and that precision printed
underneath.

## Did it settle?

The first seconds of a run on the JVM are class loading, compilation and a cold
connection pool, so a whole-run p99 is partly a measurement of starting up. The
usual fix is a warm-up setting; the evidence is that the setting is a guess,
wrong by a median of 28 seconds even when the benchmark's own author wrote it.
So the segment is found in the timeline instead:

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

The run is cut into four windows and the detector looks for the earliest second
after which no later window is materially better than the last one and the tail
holds within `TOLERANCE` of its own mean. A run that was still getting faster at
the end, and one that got slower and stayed slower, both report `NeverSettled` —
with different reasons, because they are different findings. Neither fails the
run: a tool that failed a build on a detector's opinion is a tool whose detector
gets turned off.

`TOLERANCE` is twice `Histogram.COARSE_PRECISION` and is written as a multiple
of it. Two readings of one unchanged latency can land a bucket apart, so a
tolerance at the width of that bucket would be a detector chasing which bucket a
sample fell in. It is a default rather than something the run discovered, which
is why the report prints it beside the verdict.

`result.steady` is the same run over that segment, and the run itself where
there is no segment. Nothing is discarded: every number above stays where it
was, and what was left out is reported.

```kotlin
import io.github.matthewjones372.kestrel.steady

result[placeOrder].serviceTime.p99          // the whole run, cold start included
result.steady[placeOrder].serviceTime.p99   // the same p99 without the first 20 s
result.steady.count                         // requests that left inside the segment
result.steady.startedAt                     // the run's start plus the offset
```

Goals are judged over the segment where the timeline measured what they read:
the counts, which are exact, and the target's service time. Response time and
the generator's own backlog are not kept second by second, so a goal on one of
those is judged over the whole run rather than narrowed to a segment nothing
measured — `Goal.overSteadySegment` says which, and the page says so above the
numbers. A run that never settled has all of its goals judged over all of it.

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
