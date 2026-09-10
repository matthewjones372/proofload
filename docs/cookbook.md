# Cookbook

Recipes rather than a feature list: each one is the few lines you would
actually write, and the reason it is those lines and not the obvious
alternative. Nothing here needs a service, an account or an action of ours.

The examples share a small vocabulary, declared once and reused throughout:

```kotlin
val api = http.baseUrl("https://orders.internal")

val browse = step("browse")
val placeOrder = step("place order")
val confirm = step("confirm")
val signIn = step("sign in")
val account = step("account")
```

A `step` handle carries its name, so a rename is a compile error rather than a
test that quietly asserts about a step nobody runs.

The recipes are Kotlin. Java and Scala reach the same values through
`proofload-java` and `proofload-scala`, and the calls are named in each recipe
where the spelling differs: [from-java.md](from-java.md) and
[from-scala.md](from-scala.md) are the pages, and every snippet on those two is
a line of a source set the build compiles.

## Contents

**Getting a run out of it**: [a first load test](#a-first-load-test) ·
[the same thing in Kotest](#the-same-thing-in-kotest) ·
[the same thing in a zio-test spec](#the-same-thing-in-a-zio-test-spec) ·
[run on an engine of your own](#run-on-an-engine-of-your-own) ·
[without a test framework](#without-a-test-framework) ·
[see what a scenario does before running it](#see-what-a-scenario-does-before-running-it) ·
[know how long it will take](#know-how-long-it-will-take) ·
[quieten the progress lines](#quieten-the-progress-lines)

**The vocabulary**: [what a p99 is](concepts.md#what-a-percentile-is-and-why-not-an-average) ·
[why being late is a verdict](concepts.md#why-being-late-matters-enough-to-be-a-verdict) ·
[what Little's law catches](concepts.md#littles-law-and-what-it-catches) ·
[the two clocks](concepts.md#two-clocks-and-why-one-of-them-is-the-honest-one)

**Shaping the load**: [flat, ramped, and staged](#flat-ramped-and-staged) ·
[fifty users, looping](#fifty-users-looping) ·
[stop sending on a metronome](#stop-sending-on-a-metronome) ·
[think time](#think-time) ·
[loops and conditions](#loops-and-conditions) ·
[two journeys in one run](#two-journeys-in-one-run)

**Giving users their own data**: [a function of the user number](#a-function-of-the-user-number) ·
[a fixed list](#a-fixed-list) · [a CSV file](#a-csv-file) ·
[data you do not have](#data-you-do-not-have) ·
[a token that expires mid-run](#a-token-that-expires-mid-run)

**The requests themselves**: [chain two steps with a capture](#chain-two-steps-with-a-capture) ·
[check the body, not just the status](#check-the-body-not-just-the-status) ·
[sign in once and carry the cookie](#sign-in-once-and-carry-the-cookie) ·
[follow a slow request into your traces](#follow-a-slow-request-into-your-traces) ·
[a step that is not HTTP](#a-step-that-is-not-http) ·
[gRPC](#grpc) ·
[WebSockets](#websockets) ·
[server-sent events](#server-sent-events) ·
[work that finishes somewhere else](#work-that-finishes-somewhere-else) ·
[Kafka, and the answer on another topic](#kafka-and-the-answer-on-another-topic)

**Asking the question**: [assert, or declare goals](#assert-or-declare-goals) ·
[ask what actually failed](#ask-what-actually-failed) ·
[read only the part that settled](#read-only-the-part-that-settled) ·
[find the rate it sustains](#find-the-rate-it-sustains) ·
[did the generator keep up?](#did-the-generator-keep-up)

**Keeping the answer**: [write an HTML report](#write-an-html-report) ·
[one run at a time, on the whole machine](#one-run-at-a-time-on-the-whole-machine) ·
[a baseline in GitHub Actions](#a-baseline-in-github-actions) ·
[more than one run, and a verdict worth having](#more-than-one-run-and-a-verdict-worth-having) ·
[more than one injector](#more-than-one-injector) ·
[what a statistic has been doing](#what-a-statistic-has-been-doing) ·
[send the numbers somewhere else](#send-the-numbers-somewhere-else) ·
[publish the reports to GitHub Pages](#publish-the-reports-to-github-pages) ·
[do not gate a merge on latency](#do-not-gate-a-merge-on-latency) ·
[what the comparison will refuse to say](#what-the-comparison-will-refuse-to-say) ·
[what calibration costs](#what-calibration-costs)

---

## A first load test

`@LoadTest` is `@Test` plus a `Proofload` parameter. There is nothing to
register, no base class, and no lifecycle to remember.

```kotlin
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.engine.Proofload
import io.github.matthewjones372.proofload.http.exec
import io.github.matthewjones372.proofload.junit5.LoadTest
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.scenario
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class CheckoutLoadTest {

    @LoadTest
    fun `checkout holds up at fifty a second`(proofload: Proofload) {
        val checkout = scenario("checkout") {
            exec(browse, api.get("/products"))
            exec(placeOrder, api.post("/orders").body("""{"cart":"1 anvil"}""").expecting(201))
        }

        val result = proofload.run(checkout.at(50.perSecond, over = 1.minutes))

        result[placeOrder].responseTime.p99 shouldBeLessThan 200.milliseconds
        result.failed shouldBe 0L
    }
}
```

A run takes the machine while it lasts, so two `@LoadTest` methods that start at
the same moment measure the target one after the other rather than measuring
each other. That is per JVM: a second process on the same host is not queued
behind it.

## The same thing in Kotest

`proofload()` is a suspend function that hands back the runner for the test it is
called from. No spec base class, and nothing to register: called from a spec
that registered nothing it still works.

```kotlin
import io.github.matthewjones372.proofload.kotest.proofload
import io.kotest.core.spec.style.StringSpec

class CheckoutSpec : StringSpec({

    "checkout holds up at fifty a second" {
        val result = proofload().run(checkout.at(50.perSecond, over = 1.minutes))

        result[placeOrder].responseTime.p99 shouldBeLessThan 200.milliseconds
    }
})
```

## The same thing in a zio-test spec

`ProofloadSpec` is a `ZIOSpecDefault` that already carries what a load spec
needs, so what is left of the spec is the measurement:

```scala
object CheckoutSpec extends ProofloadSpec:

  def spec = suite("checkout")(
    test("holds up at fifty a second"):
      for
        result <- measured("checkout"):
          checkout.at(50.perSecond, over = 1.minute).expecting(p99(placeOrder) under 200.millis)
      yield assert(result)(failedNone && metEveryGoal),
  )
```

Extending it brings `TestAspect.sequential` and `TestAspect.withLiveClock`. The
second is the one to know about: zio-test hands a spec a `TestClock`, so any
time the *spec* takes (a readiness retry, a `Schedule`, a timeout) never
advances and the spec hangs rather than failing. The run itself is on the wall
clock and is fine, which is what makes it hard to find. There is no timeout in
there, because the right one is the length of what is being run: add
`@@ TestAspect.timeout(...)` to the suite yourself.

`measured` runs it, writes its page under `reportsTo` and appends its table to
the job summary, and the index over that directory is written once, after the
last test. `reportsTo` defaults to `target/proofload`; a caller who wants the
result without a report has `proofload.run`.

Durations are whichever kind you already hold: `1.minute` from `zio` and
`1.minute` from Scala's `DurationInt` both work, and nothing needs a conversion
or a language flag. A run fails with a `ProofloadError`, which is `Invalid`,
`Interrupted` or `Failed`: the question a caller is asking is whether to retry.
Nothing the target did reaches that channel, because a refused connection is a
measurement.

[from-scala.md](from-scala.md) is the page, and the spec above is a shortened
version of one the build compiles and runs.

## Run on an engine of your own

Proofload runs on virtual threads and there is no second engine in the tree, but
core declares what a runner is, so the seam is real rather than promised:

```kotlin
fun interface Engine {
    fun run(simulation: Simulation): RunResult
}
```

A JUnit test class names one by implementing `RunsOn`; a class that does not
implement it runs on virtual threads, so `@LoadTest` costs an implementer
nothing:

```kotlin
import io.github.matthewjones372.proofload.junit5.RunsOn

class CheckoutLoadTest : RunsOn {

    override val engine = Actors()

    @LoadTest
    fun `checkout holds up at fifty a second`(proofload: Proofload) { ... }
}
```

and a Kotest spec names one by passing it:

```kotlin
val result = proofload(Actors()).run(checkout.at(50.perSecond, over = 1.minutes))
```

The engine you name is still wrapped so that one run has the machine at a time,
two tests that start together measure the target one after the other rather than
measuring each other, whichever engine sends them. Wrapping an already-exclusive
engine is safe: the lock notices the thread already holds it.

This is worth having with one implementation because the alternative was
selecting an engine by which `run` you imported, which makes a scenario's runner
depend on a file's import list.

## Without a test framework

A simulation is a value and `run` is an extension on it, so a `main` is enough.
This is the shape for a scheduled soak or a one-off from a shell.

```kotlin
import io.github.matthewjones372.proofload.engine.run
import io.github.matthewjones372.proofload.report.writeHtmlReport
import java.nio.file.Path

fun main() {
    val result = checkout.at(50.perSecond, over = 10.minutes).run()

    result.writeHtmlReport(Path.of("build/reports/soak.html"))
}
```

## See what a scenario does before running it

`trace` walks one user through the scenario and prints what each step did. Use
it when a step fails and you want to know which one, before spending ten
minutes of load finding out.

```kotlin
import io.github.matthewjones372.proofload.engine.trace

checkout.trace()
```

```
proofload: trace checkout
proofload:   /products     ok
proofload:   /orders       FAILED status 500 — user abandoned here
```

It is a diagnostic and returns nothing, deliberately. One pass on a cold JVM
with no schedule behind it has no number worth reporting, and a result handed
back here would reach `writeHtmlReport` looking like one that had.

A scenario is an ordinary value, so you can also just read it:

```kotlin
checkout.stepNames                                  // [/products, /orders]
checkout.at(50.perSecond, over = 1.minutes).userCount()   // 3000, before anything is sent
```

## Know how long it will take

A run says its shape before it departs and counts down while it goes:

```
proofload: checkout — 30,000 users over 10m, 2 steps each
proofload: 00:05  departed 250  in flight 3  behind 88.033us  09:55 left
proofload: 10:00  departed 30,000  in flight 41  behind 96.718us  draining
```

Nothing there is estimated. The window and the user count are the profile's own
arithmetic, available before a request leaves, and `left` is the schedule's
remainder. Past the window it says `draining` rather than counting to zero:
what is left then is the target finishing the users it was given, and this end
of the wire does not know how long that takes.

A capacity search is the one thing whose length nobody can work out in advance,
because the ladder stops as soon as it has the knee and then bisects. So it
prints a bound, and narrows it:

```
proofload: capacity — at most 10 rungs of 2s and the bisection after them, so at most 30s
proofload: rung 1 — 4/s passed, at most 28s left
proofload: rung 5 — 20/s failed, at most 20s left
proofload: rung 9 — 17/s passed, at most 12s left
```

"At most", never a forecast. A bound that is beaten leaves you pleasantly
surprised; a forecast that is missed is a tool that lied. A rung that voids says
so and stops the bound, because the generator lost ground and the answer is then
about this machine rather than about the target.

## Quieten the progress lines

When the output is somebody else's report (a CI step that parses stdout, a test
framework), hand it a quiet one:

```kotlin
import io.github.matthewjones372.proofload.Progress

val proofload = Proofload(Progress.silent)

// or a different interval
val proofload = Proofload(Progress.lines(every = 30.seconds))
```

Nothing in a tick is read from what was recorded. A live histogram read for a
percentile would put the watcher's work on the path being timed, so these are
numbers the scheduler already keeps: a run costs the same watched as unwatched.

## Watch a two-hour soak from a dashboard

The progress line is one reporter. `and` puts a second one beside it, and
`otlpEvery` pushes what the scheduler knows to a collector while the run is
still going:

```kotlin
import io.github.matthewjones372.proofload.Progress
import io.github.matthewjones372.proofload.and
import io.github.matthewjones372.proofload.engine.Proofload
import io.github.matthewjones372.proofload.otel.otlpEvery
import kotlin.time.Duration.Companion.seconds

val proofload = Proofload(
    Progress.lines() and otlpEvery(30.seconds, to = "http://collector:4318/v1/metrics", run = "soak-2026-09-03"),
)
```

```groovy
dependencies {
    testImplementation("io.github.matthewjones372:proofload-engine:0.1.0-rc4")
    testImplementation("io.github.matthewjones372:proofload-otel:0.1.0-rc4")
}
```

What is live is what the scheduler already keeps, in the names the finished
export uses:

| Series | What it says |
|---|---|
| `proofload.departed` | users handed to a thread so far |
| `proofload.in_flight` | users still running, a parked user counts here |
| `proofload.requests` | requests recorded since the last push |
| `proofload.failures` | failures recorded since the last push |
| `proofload.behind.last` | how late the last departure was |

Read `proofload.behind.last` before any of the counts. Where it is growing, the
rate you named is no longer being offered and every number under it is about a
lighter test than the one you asked for, which at minute four is a run worth
stopping, and is the whole reason for watching one.

**There is no live percentile, and that is deliberate.** A percentile is read
off the histograms the recorders are still writing to, and reading those while
a run is timing something is a lock on the path being timed: a tool that moves
what it measures reports its own weight as the target's latency. Counts and the
backlog are numbers the scheduler keeps anyway, so a watched run costs what an
unwatched one does. The percentiles arrive when the run does, through
`sendOtlp`.

Two more things worth knowing:

- **A collector that is down does not stop the run.** The first refusal is one
  line on stderr and nothing after it. Losing the dashboard is not losing the
  measurement.
- **The push happens on the sampler's thread**, so a slow collector delays the
  next sample rather than a departure. `otlpEvery` takes its own interval for
  that reason; the same applies to any reporter of your own that goes over a
  network, which `Progress.throttled(every)` wraps.

Pass the same `run` label to `sendOtlp` at the end and the live series and the
finished one join on one dashboard. The live points carry no `step`, because a
snapshot is the run rather than its steps, which is also what keeps them a
different series from the finished export's, so a run pushed live and then sent
at the end is not counted twice.

## Flat, ramped, and staged

`at` is the flat case and covers most tests. For anything else, name the shape:

```kotlin
import io.github.matthewjones372.proofload.constantRate
import io.github.matthewjones372.proofload.hold
import io.github.matthewjones372.proofload.injecting
import io.github.matthewjones372.proofload.rampRate
import io.github.matthewjones372.proofload.then
import io.github.matthewjones372.proofload.thenRampTo

checkout.at(50.perSecond, over = 10.minutes)                     // flat

checkout.injecting(rampRate(from = 0.perSecond, to = 200.perSecond, over = 5.minutes))

checkout.injecting(
    hold(10.perSecond, over = 1.minutes)                         // warm the caches
        .thenRampTo(200.perSecond, over = 5.minutes)             // climb from 10
        .then(hold(200.perSecond, over = 10.minutes)),           // and sit there
)
```

`thenRampTo` reads the rate the shape was already running at, so the ramp above
starts at 10 without you writing 10 twice. The whole thing is still a value:

```kotlin
val profile = hold(10.perSecond, over = 1.minutes).thenRampTo(200.perSecond, over = 5.minutes)

profile.over          // 6m
profile.userCount()   // 32100
profile.endRate       // 200.0/s
```

## Fifty users, looping

The closed model, which is how most people describe load and the one shape here that
measures a queue of its own making:

```kotlin
import io.github.matthewjones372.proofload.users

val soak = looping.at(users(50, over = 10.minutes))
```

Fifty users start at once and each restarts the scenario when it finishes.

**Read this before the numbers.** When the target slows down, a closed run
sends *less*, so its report shows a service that stayed fast while doing less
work. That is coordinated omission, and it is why the open model is the default
here. The page says so at the top of a closed run and nowhere else.

What a closed run does not report, because it never promised a departure:

- **no lateness.** `behind` and the per-second lateness are empty rather than
  zero, because a generator is not late for a departure nobody promised, and zero
  would read as perfect punctuality;
- **one clock, not two.** Response time counts from the departure the profile
  promised, and after each user's first journey there is none, so response time
  and service time are the same number;
- **no `offered`, no `heldScheduleFor`**, and a `keptSchedule` goal is refused
  where it is written rather than judged against a schedule that never existed;
- **no arrival spacing.** That figure needs one thread seeing departures in
  order, and a population has none.

What it does report is the rate it achieved, which is the only rate it has, and
[Little's law](concepts.md#littles-law-and-what-it-catches), which needs no promised
departure and is the one check a fixed population makes better than an open
run, because the concurrency it predicts is a number you chose.

`users(...)` cannot go inside `then`, `randomized` or a replay: those shape
departures, and this has none to shape.

## Stop sending on a metronome

Evenly spaced departures understate queueing at the rate they claim to be
testing: real arrivals are close to Poisson, and queueing delay scales with the
variability of arrivals rather than only with their mean.

```kotlin
import io.github.matthewjones372.proofload.randomized

checkout.injecting(constantRate(50.perSecond, over = 10.minutes).randomized(seed = 7))
```

There is no default seed. An unseeded random run is not one anybody can
reproduce, and the report prints the seed so a reader can.

## Think time

A real user reads the page before clicking. `pause` is that gap, and it happens
when the run does rather than when the file is loaded:

```kotlin
val checkout = scenario("checkout") {
    exec(browse, api.get("/products"))
    pause(2.seconds)
    exec(placeOrder, api.post("/orders").body("""{"cart":"1 anvil"}"""))
}
```

A pause has no name and no row in the report. It is not a step that took two
seconds; it is the absence of one.

A constant pause has a consequence worth knowing: two hundred users that reach
it together leave it together, to the scheduler's resolution, and click again in
the same instant. That is the metronome [Poisson arrivals](#stop-sending-on-a-metronome)
took out of the arrivals, put back inside the journey. Draw the wait instead:

```kotlin
val checkout = scenario("checkout") {
    exec(browse, api.get("/products"))
    pause(exponential(mean = 2.seconds))
    exec(placeOrder, api.post("/orders").body("""{"cart":"1 anvil"}"""))
}

val soak = checkout.at(50.perSecond, over = 10.minutes).thinkingFrom(seed = 20260826)
```

`constant`, `exponential`, `lognormal(median, sigma)` and `uniform(from, until)`
are the shapes. A scenario that draws is refused without a seed, because an unseeded
random run is not one anybody can reproduce, and a scenario of constants still
needs none. Each user's waits come from that seed and its own user number, so
user 4,001 parks the same tomorrow whatever the target did today, and the report
names the distribution and the seed beside the arrivals line.

## Replay the arrivals you actually had

Poisson is closer to production than a metronome and still not production: real
traffic bunches, and the bunching is what fills a queue. If you have a log of
when requests arrived, send that:

```kotlin
val friday = arrivalsFrom(csv(Path.of("friday-peak.csv")), column = "at")
friday.count      // 1,204,663 — answered before anything is sent
friday.span       // 1h
friday.cov        // 2.71, how bursty that hour really was

val peak = checkout.injecting(friday.replaying(from = 40.minutes, window = 10.minutes, scaled = 2.0))
```

`from` and `window` cut the capture in its own time, before the scaling, so ten
captured minutes at twice the rate is five minutes of run.

Scaling multiplies every gap by the same number, which leaves the coefficient of
variation exactly where it was, and that is what lets the report print the
capture's burstiness beside the run's and mean something. Proofload will not thin
the arrivals to scale them: thinning drives a point process towards Poisson,
which is the shape you replayed a capture to avoid.

A replay cannot be `randomized`, and says so: a capture is already an arrival
process, and drawing from it would put a model back where the measurement was.

## Loops and conditions

A scenario is a tree, not a list, so a loop is a step holding steps rather than
a body copied out:

```kotlin
val browsing = scenario("browsing") {
    exec(signIn, api.post("/session").body(credentials))

    repeat(10) {
        exec(browse, api.get("/products"))
        pause(2.seconds)
    }

    during(5.minutes) {
        exec(poll, api.get("/notifications"))
        pause(30.seconds)
    }

    doIf({ it[tier] == "gold" }) {
        exec(concierge, api.get("/concierge"))
    }
}
```

`repeat` deliberately shadows `kotlin.repeat` inside a scenario. The stdlib one
would build the body ten times over, and the report would carry ten rows for one
step; this builds one step that runs ten times, so `/products` is one row with
ten times the samples.

`during` reads its own clock between iterations rather than waiting on anything,
so no thread is parked. A user still looping when the profile's window closes
finishes its iteration and extends the run, because the alternative is cutting a user
off mid-journey and counting the half of it that happened.

`doIf` asks the session and nothing else. A condition over what the target
answered would be a step nobody named, and a run that took one could not say
beforehand what it was going to send.

Because a step can now run many times per user, the report says both:

```kotlin
result[browse].count      // 300 requests
result[browse].reached    // from 30 users
```

## Two journeys in one run

Most real load is a mix. Name each journey as an arm with its own rate and its
own data:

```kotlin
import io.github.matthewjones372.proofload.Arm
import io.github.matthewjones372.proofload.Simulation

val simulation = Simulation(
    arms = listOf(
        Arm(browsing, constantRate(500.perSecond, over = 10.minutes)),
        Arm(checkout, constantRate(20.perSecond, over = 10.minutes)),
    ),
)

proofload.run(simulation)
```

The arms are merged into one departure schedule rather than booked one after
another, because a run that sent all of one arm and then all of the next would hand the
arrivals recorder a gap running backwards, and report a spacing nothing
produced. Each arm is fed from its own feeder and numbers its users from zero,
so `feed(customer) { "customer-$it" }` on two arms is two independent sequences.

The run lasts as long as its longest arm. Two arms may not share a step name: a
step name is one row of the report, and a shared one would either merge into a
row describing neither or be qualified behind your back. Rename one and the
constructor tells you which. A two-arm run also refuses to compare against a
one-arm baseline, and names the arm that is missing.

## A function of the user number

Every virtual user starts with its own data, so a cache in front of the target
cannot answer for all of them:

```kotlin
import io.github.matthewjones372.proofload.feed
import io.github.matthewjones372.proofload.fedBy
import io.github.matthewjones372.proofload.sessionKey

val customer = sessionKey<String>("customer")

val checkout = scenario("checkout") {
    exec(browse, api.get("/customers/{customer}/products"))   // filled from the session
}

checkout.at(50.perSecond, over = 1.minutes)
    .fedBy(feed(customer) { user -> "customer-$user" })
```

A feeder is a function of the user's number rather than a cursor over a source,
so there is nothing to lock on the path every request takes, nothing to run out
of, and user 4,001 gets the same data tomorrow as it did today.

Java says this as `Feeders.of(customer, user -> "customer-" + user)` and
`Feeders.fedBy(simulation, feeder)`; Scala says `feed(customer)(user =>
s"customer-$user")`, with `+` to combine two and `feedFrom` for a fixed list.

A request body reads the session the same way, so the thing being posted varies
per user too:

```kotlin
exec(placeOrder, api.post("/orders").body("""{"customer":"{customer}","cart":"1 anvil"}"""))
```

Ten thousand users sending one identical order measure whatever the target does
with a duplicate, dedupes it, serves it from cache, collides on a unique index,
takes the idempotency key at its word, and the page reports that as the latency
of placing an order.

Only an identifier between braces is a placeholder, so a JSON document's own
braces are content and arrive as written. A `{name}` the session has nothing
under fails the step with `no name captured` rather than sending the braces to
the target, which would file somebody else's answer under this step.

A body too large to hold is opened instead of written:

```kotlin
exec(upload, api.put("/uploads/{id}").bodyFrom(bytes = size) { Files.newInputStream(archive) })
```

The lambda is called once per attempt, so a retry and a redirect each get their
own stream, because a stream is read once, and a body that could only be sent once
would arrive empty on every attempt after the first. Give `bytes` where you know
the length and it is sent as `content-length`; leave it out and the request is
chunked. Nothing fills `{name}` in a streamed body: the substitution reads a
string, and a stream is not one.

## A fixed list

```kotlin
import io.github.matthewjones372.proofload.feedFrom

val sku = sessionKey<String>("sku")

checkout.at(50.perSecond, over = 1.minutes)
    .fedBy(feedFrom(sku, listOf("anvil", "rocket", "birdseed")))
```

It wraps round at the end. A feeder that ran out would end a load test for a
reason that has nothing to do with the target.

Two feeders compose, and the later one wins where they fill the same key:

```kotlin
checkout.at(50.perSecond, over = 1.minutes)
    .fedBy(feed(customer) { "customer-$it" } + feedFrom(sku, skus))
```

## A CSV file

Read once, before the run, and held as a value:

```kotlin
import io.github.matthewjones372.proofload.csv
import io.github.matthewjones372.proofload.feeding

val customer = sessionKey<String>("customer")
val tier = sessionKey<String>("tier")

val accounts = csv(Path.of("src/test/resources/accounts.csv"))

checkout.at(50.perSecond, over = 1.minutes).fedBy(accounts.feeding(customer, tier))
```

Each key fills from the column the header gave the same name, so there is no
second place to keep the mapping. A key naming a column the file does not have
fails when the feeder is built, naming the column and listing the ones that are
there, before the run departs anything, rather than on user one.

A CSV has no types, so a key that is not a `String` names its own conversion:

```kotlin
val account = sessionKey<Long>("account")

accounts.feeding(account) { it.toLong() }
```

The conversion runs once per row while the feeder is built, never on the path a
departure takes.

Held in memory rather than streamed, for the reason everything else here is: a
file read between a departure and the request it makes is measured as the
target's latency. One session is built per row up front, so feeding a user is an
index into a list, nothing allocated, nothing locked, and it wraps round rather
than running out. The grammar is a deliberately small part of RFC 4180 (quoted
fields and doubled quotes inside them) because core carries no dependencies and
anything wider is a CSV library.

## Data you do not have

A thousand-row file cycled for a million users keeps every one of those rows in
every cache the target has, so part of the p99 on the page is a hit rate the
test invented. Drawing a key uniformly at random is wrong the other way: it
misses every cache. Real traffic does neither. What decides the number is
how many distinct keys there are and how unevenly they are asked for, and
`proofload-arbs` is where both are said out loud:

```kotlin
import io.github.matthewjones372.proofload.arbs.map
import io.github.matthewjones372.proofload.arbs.oneOf
import io.github.matthewjones372.proofload.arbs.zipf
import io.github.matthewjones372.proofload.feed

val customerId = zipf(keys = 1_000_000, skew = 1.1).map { "customer-$it" }
val basket = oneOf("anvil", "rocket", "birdseed")

checkout.at(50.perSecond, over = 1.minutes)
    .fedBy(feed(customer) { customerId at it } + feed(sku) { basket at it })
```

```groovy
dependencies {
    testImplementation("io.github.matthewjones372:proofload-arbs:0.1.0-rc4")
}
```

`at(userNumber)` and never `next()`: a generator is a pure function of the
user's number, so a run replays, the value user 8,412 sent is re-derivable from
its number, and fifty thousand virtual threads share no source to contend on.
It is drawn where the feeder runs, before the departure, rather than inside a
step body, because a value made up on the measured path allocates there, and the
collection pause it eventually buys is recorded in `hiccups` and read as the
target's latency.

`zipf` answers a rank rather than a key, because a generator that formatted
strings would have guessed your id scheme; `map` turns it into whatever yours
is. `uniform` is the flat keyspace, `digits` and `uuids` are ids of a fixed
shape, and `weighted` is a traffic mix stated as proportions.

These are not kotest's `Arb`. That one leans towards edge cases (the empty
string, `MIN_VALUE`, the boundary) because it is hunting bugs, which is the
wrong bias for load.

A `Feeder` is a function of the user's number and nothing else, so nothing
downstream can work out what a run's keys were drawn from by looking at it.
`drawing` is where you say it, beside `fedBy`:

```kotlin
import io.github.matthewjones372.proofload.drawing

checkout.at(50.perSecond, over = 1.minutes)
    .fedBy(feed(customer) { customerId at it } + feed(sku) { basket at it })
    .drawing(customerId.shape, basket.shape)
```

The shapes travel with the result, onto the report, `Data: zipf(keys=1000000,
skew=1.1), seed 0.` beside the arrival process, on the HTML page and in the job
summary, and into a baseline file. A comparison refuses two runs that named
different ones rather than reporting the cache hit rate one of them bought as a
regression. A run that named none compares exactly as it did before: every baseline written so far has no shape in it, so the
strict reading would refuse them all. The cost of that is stated rather than
hidden: moving a CSV-fed run onto a generator is a change no comparison flags.

### The same thing in a plan

A plan file draws too, so a generated plan is not ten thousand requests for one
row. `draw` names a session key and a generator, and `{name}` in a path or a
body reads it:

```yaml
proofload:  plan/1
baseUrl:  https://shop.internal
scenario: catalogue
seed: 7
draw:
  sku:    {zipf: {keys: 1000000, skew: 1.1}}
  page:   {uniform: {from: 1, to: 20}}
  region: {oneOf: [emea, apac, amer]}
steps:
  - name: open product
    get: '/regions/{region}/products/{sku}'
    declared: [404]
load:
  rate: 200/s
  over: 1m
```

`{uniform: 500}` draws `0` to `499`; the `from`/`to` form draws the inclusive
range a contract states. Every drawn value arrives as a string, because that is
what a path reads. Each key is seeded from `seed` and its own name, so two keys
never draw in step with one another. Without that, customer 41 would always
buy item 41 and nothing in the report would show it.

`proofload from-openapi` writes the draw itself where the document bounds a
parameter: `minimum: 1, maximum: 500` becomes `{uniform: {from: 1, to: 500}}`,
and an `enum` becomes every value it lists. Where the document bounds nothing,
one legal value is substituted as before. Inventing a range it never stated
would be inventing the cardinality this section is about.

`proofload emit` prints the Kotlin above, seeds included, so the file and the
source draw the same data.

```groovy
dependencies {
    testImplementation("io.github.matthewjones372:proofload-plan:$proofloadVersion")
}
```

## A token that expires mid-run

Fetching a token inside a step puts that round trip in one request out of a few
hundred, and the p99 becomes a measurement of your identity provider.
`refreshing` keeps it off the measured path:

```kotlin
import io.github.matthewjones372.proofload.refreshing

val token = refreshing(every = 4.minutes) { fetchToken() }

val checkout = scenario("checkout") {
    exec(placeOrder) {
        send(api.post("/orders").header("authorization", "Bearer ${token.current}"))
    }
}

// afterwards
token.stop()
```

The first fetch happens when `refreshing` is called, before the run, so it
cannot land inside whichever virtual user reads it first. Every later fetch runs
on a daemon thread no departure is submitted to. `current` is a volatile read of
a value already in hand and allocates nothing.

## Start from traffic you already have

Every scenario in this book was typed by hand, and that is the main cost of
adopting any load tool. If you already have the flow in a browser or a proxy,
export a HAR and read it in:

```bash
./gradlew :proofload-record:run --args="checkout.har --package com.acme.load --out src/test/kotlin"
```

```
proofload: 2 steps from 3 recorded requests, at src/test/kotlin/com/acme/load/Checkout.kt
proofload: captured orderId
proofload: 1 credential header(s) dropped, each left as a TODO
```

The output is Kotlin source you edit and commit, not a `Scenario` read at run
time. A recording is a first draft (most of it wants deleting) and a draft
re-read on every run is one nobody edits, so the forty CDN requests and the
expired token stay in it forever.

What it does with the recording:

- **One `exec` per request**, named `METHOD /path`, in the order they were made.
- **A value one answer produced and a later request used becomes a `capture`
  and a `{name}`.** That chain is what a hand-written scenario gets wrong: the
  transcription hard-codes the id, nothing fails, and the run measures a
  lighter experiment than the one you meant. The nearer answer wins, so a value
  seen twice is credited to the response that actually put it there. A value
  too short to be sure of gets a comment instead of a capture.
- **Runs of one path differing by a segment become one step**, with a comment
  saying how many it stood for. Forty `GET /products/17` are one endpoint under
  load and forty rows nobody reads.
- **Static assets are excluded**, whatever else you pass: a page load is forty
  requests to a CDN and one to the API, and including them measures somebody
  else's cache. `--include` and `--exclude` take a regex over the path.
- **Every credential is dropped.** `authorization`, `cookie`, `set-cookie`,
  `x-api-key` and anything whose value parses as a JWT are replaced by a `TODO`
  naming the header. There is no flag to turn this off, because a switch somebody sets
  once and forgets is a token in a public repository. Put a real credential
  there, or fetch one off the measured path with `refreshing` (above).
- **Think time is a comment.** The gaps between recorded requests are one
  person's, not a rate line; they are printed and commented out, and the profile
  is yours to state.

What it does not do: record traffic. That is a solved problem with several tools
that already do it, and a proxy means a CA certificate, a browser configuration
and a man-in-the-middle on somebody's laptop. Chrome, Firefox, Charles,
mitmproxy and every API client already export HAR; this reads the file.

`docs/examples/checkout.har` is the recording behind this book's own checkout,
and `proofload-record/src/test/.../generated/Checkout.kt` is what it generates,
checked in, compiled and formatted by the same build as everything else, which
is what keeps "the output compiles" from being a claim.

## Chain two steps with a capture

```kotlin
val orderId = sessionKey<String>("orderId")

val checkout = scenario("checkout") {
    exec(
        placeOrder,
        api.post("/orders")
            .body("""{"cart":"1 anvil"}""")
            .expecting(201)
            .capture(orderId) { response -> response.header("location") },
    )
    exec(confirm, api.get("/orders/{orderId}/confirmation"))
}
```

A key carries its type, so there is no cast to read one back and no string
looked up in a map of `Any`. A capture that finds nothing fails the step it was
declared on rather than the step that would have used the value, which is where
you would have gone looking anyway.

## Check the body, not just the status

A target under load answers 200 with an error page more often than people
expect.

```kotlin
exec(
    placeOrder,
    api.post("/orders")
        .body("""{"cart":"1 anvil"}""")
        .expecting(201)
        .checking("has an order id") { it.body.contains("\"id\"") }
        .timeout(2.seconds),
)
```

A failed check is a failed step with the check's name as its reason, so the
report says `has an order id` rather than `assertion failed`.

## Measure a download without holding it

A response is read into a `String` so a check can read it and a capture can take
values out of it. For the export nobody reads, that is 200 MB per user:

```kotlin
import io.github.matthewjones372.proofload.http.http

val api = http.baseUrl("https://api.example.com")

exec(download, api.get("/exports/{id}").discardingBody())
```

The bytes are counted as they arrive and let go. `Response.bytes` is what came
back and `Response.body` is empty, which is the finding worth having, because a
download that returned 4 KB instead of 200 MB took no time at all and otherwise
reads as a very fast target.

`Response.bytes` is on every response, not only a discarded one. Where the body
was kept it is what that body encodes to as UTF-8 rather than a count taken off
the wire, which is the same number for a target that did not name another
charset.

**A check or a capture on a discarding step is refused where it is written**,
not at run time: both read a body that will not exist, and a step that silently
checks an empty string is a green test about nothing. Drop one or the other.

A redirect is still followed (a hop reads `Location` from the headers, not from
the body) and so is a retry.

## Retry, without burying the retry

Real clients retry a 503. Proofload will too, if you ask, and asking is the
point, because a target that fails one request in ten looks perfect behind two
retries:

```kotlin
exec(
    placeOrder,
    api.post("/orders")
        .expecting(201)
        .retrying(times = 2, on = { it.status == 503 }, backingOff = 100.milliseconds),
)
```

The step's latency is the **last** attempt's, and the earlier attempts and the
waits between them are counted rather than added to it:

```kotlin
result[placeOrder].count      // 480 requests
result[placeOrder].attempts   // 512 trips to the target
```

That split is the whole reason this is a feature rather than a loop you write
yourself. A retry written inside a step body is timed as one long request, so a
p99 climbs by the backoff you chose and describes this tool's patience rather
than the target.

## Send through a different client

`docs/what-it-costs.md` measures the shipped path at a lower bound of a couple
of thousand a second on four shared cores, and that is the JDK client's number
rather than Proofload's. A caller who needs more hands in a transport:

```kotlin
val api = http.baseUrl("https://orders.internal").over(FasterClient())
```

A `Transport` is one method (a `Request` in, an `Exchange` out) and it lives
on the `Http` value, so a run against two services can speed up one and leave
the other alone.

What a transport may **not** change is what a number means. The redirect walk,
the per-user cookie jar, the `traceparent`, the status and the body checks all
stay above the seam, and it is handed no `StepScope`, so it cannot write to a
user's session. It owes exactly two failures (`TimedOut` where the target did
not answer in time and `Threw(class)` for anything else) and no duration:
service time is measured by the engine around the step, and a clock inside a
transport would be a third one to reconcile.

`TransportContractTest` in `proofload-http` is what an implementation is judged
against: a 503 is an answer and not a failure, a silent target is
`Failed(TimedOut)`, a refused connection is `Failed(Threw("ConnectException"))`,
and a redirect is never followed of the transport's own accord.

## Sign in once and carry the cookie

```kotlin
val api = http.baseUrl("https://shop.internal").withCookies()

val signedIn = scenario("signed in") {
    exec(signIn, api.post("/session").body(credentials).expecting(302))
    exec(account, api.get("/account"))    // carries the cookie the sign-in set
}
```

The jar is in each user's own session, not on the client. There is one
`HttpClient` for the whole run so that TLS handshakes are not measured, and
`java.net.CookieHandler` hangs off the client, so a jar there would be one jar
shared by fifty thousand users taking turns being one logged-in person.

Name and value only: no expiry, no path or domain matching, because a load test
sends to one base URL. It is off unless asked for.

Redirects are off unless asked for too: a 302 is the failure it was, under the
step that got it, until `following()` says otherwise:

```kotlin
exec(signIn, api.post("/session").body(credentials).following())
```

The hops happen in the step, not in the client: the shared `HttpClient` is still
built with `Redirect.NEVER`, so a redirect this tool did not follow deliberately
is still a finding rather than a timing for a page nobody asked for. Cookies set
by a 302 are carried to the page it points at. 301, 302 and 303 are followed as
a bodyless GET; 307 and 308 keep the method and body. `expecting()` judges the
response the chain lands on, so a sign-in that ends 200 is written
`.following()` rather than `.expecting(302)`. A chain longer than `max` fails
under `TooManyRedirects`.

## Follow a slow request into your traces

`traced()` puts a W3C `traceparent` and a synthetic-traffic `baggage` entry on
every request, so a slow measurement has a trace id to follow into whatever the
target exports its spans to.

```kotlin
val api = http.baseUrl("https://orders.internal").traced()
```

The `baggage` entry marks the traffic as synthetic, which is what lets the
receiving side keep it out of its own SLO dashboards. `traced()` and
`withCookies()` compose in either order.

## A step that is not HTTP

`exec` takes a block as readily as a request, and the block is timed the same
way. This is the seam for a database, a queue client, a gRPC stub, or anything
else you already have a Kotlin API for.

```kotlin
val settle = step("settle")

val ledger = scenario("ledger") {
    exec(settle) {
        val account = this[customer] ?: return@exec fail("no customer fed")
        val outcome = ledgerClient.settle(account)
        if (!outcome.ok) fail(outcome.reason)
    }
}
```

`fail` marks the step failed and returns a reason for the report; it does not
throw, because a declared failure and a bug in the generator should not arrive
by the same route. The first reason wins: a timeout that follows a 503 is the
503's doing, and renaming it loses which one to go and fix.

## WebSockets

Two handshakes, each its own timed step:

```kotlin
import io.github.matthewjones372.proofload.websocket.close
import io.github.matthewjones372.proofload.websocket.open
import io.github.matthewjones372.proofload.websocket.ws

val feed = ws.baseUrl("wss://prices.internal")
val connect = step("connect")
val disconnect = step("disconnect")

val streaming = scenario("streaming") {
    open(connect, feed.at("/prices"))
    pause(30.seconds)
    close(disconnect)
}

streaming.at(200.perSecond, over = 5.minutes)
```

`open` times the upgrade alone, from the request leaving to the server's 101
completing it. It is not a first message and not the first byte of one: nothing
here says when data starts to flow.

`close` times what the target took to let go, not what the write took: the sample
runs from the Close frame being written to the far end's Close arriving back.

Between them, `send` and `awaiting` are two steps because they are two
questions:

```kotlin
import io.github.matthewjones372.proofload.Correlation
import io.github.matthewjones372.proofload.websocket.awaiting
import io.github.matthewjones372.proofload.websocket.send

val subscribe = step("subscribe")
val ticks = step("ticks")

val streaming = scenario("streaming") {
    open(connect, feed.at("/prices"))
    send(subscribe, feed.text("SUB ACME"), keyedBy = Correlation { it[order] ?: 0L })
    awaiting(ticks, count = 100, within = 30.seconds)
    close(disconnect)
}
```

`send` is timed for the write alone and waits for nothing; the sample ends when
the client reports the frame written. `awaiting` records **one sample per
answer**, each measured from the send it answers, so `awaiting(count = 100)` is
a hundred samples under one name and the report draws the messages' own
distribution. Keeping the two steps apart is what stops a slow target being
reported as a slow write.

Answers pair with sends in the order the sends left, which is the order one
socket delivers them in. A message that arrives with nothing outstanding is
counted rather than timed, and the counts read off the connection:

```kotlin
connection.matched
connection.unsolicited
connection.outstanding(within)   // Outstanding(unmatched, inFlight), as everywhere else
```

The wait is bounded and is signalled by the close as well as by an arrival, so a
far end that hangs up fails the step at once instead of parking a user for the
rest of the run.

One caveat worth knowing: the samples are placed on the timeline at the moment
the wait finished rather than each at the second its message arrived, the
client's reader thread counts from its own connection, not from the run. The
durations are each message's own; only where they sit along the run is coarse.
A wait that times out reports the answers that did arrive, and then fails.

One socket per user, which is the opposite of the shared HTTP client and for the
opposite reason: a stream test is about how many connections a target holds, so
amortising the handshake would remove the thing being measured. Expect file
descriptors to bound your user count long before the scheduler does.

## Server-sent events

A feed is not a round trip. A plain GET at a stream endpoint measures the moment
the target agreed to start talking, and a feed that opens instantly and then
goes silent reads exactly like one delivering sixty events a second. So an SSE
stream is opened once and read with two verbs, because there are two honest
numbers in it:

```kotlin
import io.github.matthewjones372.proofload.http.cadence
import io.github.matthewjones372.proofload.http.firstEvent
import io.github.matthewjones372.proofload.http.open
import io.github.matthewjones372.proofload.http.sse
import io.github.matthewjones372.proofload.http.stopReading

val fills = sse.baseUrl("https://feeds.internal").traced().at("/fills")

val watching = scenario("watching") {
    open(opened, fills)
    firstEvent(first, within = 5.seconds)
    cadence(each, count = 99, within = 60.seconds)
    stopReading(done)
}
```

`open` ends when the response head arrives, the target agreeing to stream, not
saying anything. `firstEvent` is the round trip to the first event, comparable
with a plain request's latency. `cadence` is one sample per event after it, each
measured from the event before: the rate the feed actually delivered at. A
hundred events is `firstEvent` plus `cadence(count = 99)`.

They are kept apart on purpose. Timed from the request, the *k*th event climbs
with *k* and fills the histogram with a number describing the feed's length
rather than the target's speed; put in with the round trip, the two average into
a figure describing neither. A `cadence` before any `firstEvent` fails with
`NoFirstEvent` rather than handing the round trip out as the first gap.

**A heartbeat is not an event.** A comment line (`:` and anything after it) is
counted on the stream and satisfies no wait:

```kotlin
session[eventStream]?.delivered    // events
session[eventStream]?.heartbeats   // comment lines, counted and never timed
```

Counted as events they would let a target that has said nothing report as a busy
one. A feed that only heartbeats times out, which is what it earned.

**Nothing reconnects.** `retry:` and `Last-Event-ID` are ignored, and a far end
that lets go fails the waiting step with `StreamEnded`. A generator that
reconnected would hide the disconnection it exists to report, and the second
connection would be a second experiment on the same row.

**Nothing is parsed.** The frame fields are read only far enough to find where
one event ends and the next begins. A JSON parser on this path would be
measuring Jackson.

`stopReading` cancels the subscription and drops the connection. It is not
called `close` because SSE negotiates no closing handshake: there is nothing to
send and nothing to wait for, and its sample is the cancel, which happens in
this process.

## Work that finishes somewhere else

Some systems answer on another topic, another queue, or a webhook. The latency
that matters is not the ack but the departure the profile promised to the
answer arriving.

```kotlin
import io.github.matthewjones372.proofload.Correlation
import io.github.matthewjones372.proofload.InMemoryCompletions
import io.github.matthewjones372.proofload.completing

val tradeId = sessionKey<Long>("tradeId")
val submitted = step("submitted")

val settlements = InMemoryCompletions()

val trades = scenario("trades") {
    emit(
        submitted,
        action { broker.publish(this[tradeId]!!) },
        keyedBy = Correlation { session -> session[tradeId] ?: 0L },
    )
}

val result = proofload.run(
    trades.at(5_000.perSecond, over = 5.minutes)
        .completing(submitted, from = settlements, drainingFor = 30.seconds),
)

result.unmatched   // departed, and never answered for
result.inFlight    // departed, and the run ended before the window closed
```

The two counters are kept apart on purpose. `unmatched` is work the system lost;
`inFlight` is work you did not wait long enough for. Reporting them as one number
would let a short drain window read as a broken pipeline.

`InMemoryCompletions` is what stands in for a broker until a module carries one:
call `observe(id)` from wherever your consumer runs.

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

Each goal becomes a `Verdict` carrying what was measured and, where it missed,
by what share of the target, "51% over" reads the same whether the limit was
200 ms or two seconds, and tells you whether you are looking at tuning or at
design.

`goodput` is worth reaching for over a bare percentile: it is the share of
requests that both succeeded and came back in time, so a target that gets fast
by failing does not pass it.

`keptSchedule` is the one that guards the rest. It asks whether the *generator*
kept to its own schedule; where it did not, every latency underneath includes a
queue this tool made, and the numbers are describing the injector.

## Judge a goal where it was asked

A staged run's aggregate is a mixture. A goal judged against it can be met
because the ramp was long enough and easy enough to pull the total under the
line, which is a green test somebody earned by editing the profile:

```kotlin
import io.github.matthewjones372.proofload.inEveryStage
import io.github.matthewjones372.proofload.p99
import kotlin.time.Duration.Companion.milliseconds

checkout.at(hold(100.perSecond, over = 2.minutes) then rampTo(1000.perSecond, over = 5.minutes))
    .expecting(p99(placeOrder) under 300.milliseconds inEveryStage)
```

One `Verdict` per stage, each naming the stage it is about, and the run meets it
only where every stage does. The report puts each on the stage row it belongs
to rather than in a list repeating the goal's name once per stage.

Declare the plain goal beside it where you also want the aggregate, since it is the
number people compare between builds, and it is a different question:

```kotlin
.expecting(
    p99(placeOrder) under 300.milliseconds inEveryStage,
    p99(placeOrder) under 300.milliseconds,
)
```

`keptSchedule inEveryStage` is the one that pays for itself twice: a run that
held its schedule through the flat two minutes and lost it climbing is a run
whose ramp found the ceiling, and the aggregate answer hides exactly that.

**A stage verdict can say it cannot tell.** A stage's numbers are the
timeline's own buckets rather than a step's, so they are one significant digit;
a goal missed by less than that width is inside the measurement rather than
outside the limit, and it reports as unresolvable instead of as a red tick
somebody chases. The run still meets it, and the page says why.

## Ask what actually failed

A reason is a value, not a string, and the module that made the request is the
one that names it:

```kotlin
import io.github.matthewjones372.proofload.Said
import io.github.matthewjones372.proofload.Threw
import io.github.matthewjones372.proofload.TimedOut
import io.github.matthewjones372.proofload.http.CheckFailed
import io.github.matthewjones372.proofload.http.HttpStatus
import io.github.matthewjones372.proofload.http.NothingCaptured

result[placeOrder].failedWith(HttpStatus(503))        // the target said no
result[placeOrder].failedWith(TimedOut)               // it was up and too slow
result[placeOrder].failedWith(Threw("ConnectException"))
result[placeOrder].failedWith(CheckFailed("has an order id"))
```

Which is the point of the type: a string could be counted and nothing else:

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

Declare your own where you do have a type. Any value implementing `Reason`
works, and it must be a value (a data class or an object) because a reason is
a map key that gets merged across recording shards and again across runs:

```kotlin
data class LedgerRejected(val code: String) : Reason {
    override val described: String get() = "ledger $code"
}
```

Past twenty distinct reasons on one step the rest are counted under `Other`. A
reason with an order id in it is a key per request, and a report that listed
them all would be a list of one-offs rather than something to act on.

## Read only the part that settled

A run's first seconds are class loading, JIT and a cold cache. `steady` is the
segment after the run settled, found rather than assumed:

```kotlin
import io.github.matthewjones372.proofload.steady
import io.github.matthewjones372.proofload.steadyState

result.steadyState        // SteadyState.From(offset) or NeverSettled
result.steady[placeOrder].responseTime.p99
```

Nothing is discarded by default: `result` still holds the whole run, and
`steady` gives back the whole run where it never settled rather than an empty
one. A goal that reads something a second does not keep, the generator's own
backlog, a failure reason, is judged over the whole run instead, because
narrowing it to a segment nothing measured would be inventing the answer.

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
capacity.voided     // true if a rung fell behind: the ceiling is this tool's, not the target's
```

It climbs a ladder until a goal misses, then bisects between the last rung that
passed and the first that did not. Pure bisection would be fewer runs, but it
assumes every rate above a failing one also fails and it leaves no curve behind.

Check `voided` before quoting the answer. A rung where the generator itself fell
behind found this tool's ceiling rather than the target's, and the number is
about the machine you ran it on.

`Rate` is a value class, so the search is `Searches.sustainable(checkout, upTo,
holding, goals)` from Java and `checkout.sustainable(upTo, holding, goals*)`
from Scala, where `capacity.rate` is an `Option[Rate]` and `capacity.curve` a
`Seq[Rung]`.

## Did the generator keep up?

The first question to ask of any result, and the one most tools do not answer:

```kotlin
result.fellBehind()   // the generator did not keep its own schedule
result.lostGround()   // and the backlog was still growing at the end
result.behind.p99     // how late the late departures were
```

Where `fellBehind()` is true, every latency in the run includes time spent
waiting in a queue this tool created, coordinated omission, which is the
default bug in a load generator rather than an exotic one. Response times are
the ones that carry it, and a response-time goal on such a run fails; the
failure is the finding.

The run is not wasted. Service time was measured from the departure that
actually happened, so it is still a true measurement of the target, at the
load that reached it rather than the load you asked for:

```kotlin
result.offered?.asked          // 2500/s — what the plan asked for
result.offered?.left           // 1923/s — what actually left
result.heldScheduleFor         // 11s — before the first second that lost ground
result.latePerSecond[12].p99   // how late that second's departures were
```

So the answer to "it fell behind, now what" is: re-run at a rate the machine
held, and read the service times of this run as the target at `left` in the
meantime. Proofload will not quietly lower the rate for you mid-run: a run that
throttles itself measures a load it then does not report, which is the bug this
tool exists to close. A [capacity search](#find-the-rate-it-sustains) is the
supported way to adapt, because every rung is a separate, labelled run.

`arrivals` says the same thing from the other side: the spacing the run actually
produced, and its coefficient of variation.

Scala reads all of this without naming a file class: `result.fellBehind`,
`result.lostGround`, `result.ranOutOfRoom`, `result.concurrency` for Little's
law, and `result.offered` as an `Option[Offered]` whose `asked`, `left` and
`over` are the numbers above.

## Write an HTML report

One self-contained file, with the data, the stylesheet and the script inline, so it
opens from a `file://` URL and uploads as a CI artifact unchanged.

```kotlin
import io.github.matthewjones372.proofload.report.writeHtmlReport

result.writeHtmlReport(Path.of("build/reports/proofload/checkout.html"))
```

```
proofload: report written to build/reports/proofload/checkout.html
proofload:   file:///home/you/project/build/reports/proofload/checkout.html
```

It takes the optional arguments that make it say more, and each one adds a
section rather than changing a number:

```kotlin
result.writeHtmlReport(
    path,
    comparison = result.against(readBaseline(path)),   // this run against the last
    floor = proofload.calibrate(),                       // what this machine can resolve
    differences = listOf(difference),                  // several runs a side
)
```

A capacity search has a page of its own, with the curve on it:

```kotlin
capacity.writeHtmlReport(Path.of("build/reports/proofload/capacity.html"))
```

Kotlin's default arguments do not cross a language boundary, so Scala has its
own defaults over the same call: `result.writeHtmlReport(path)`,
`result.markdown` and `result.appendToStepSummary()`, with
`proofload.writeHtmlReport(result, path)` and the rest as effects in a zio-test
spec. Nothing there is a hand-placed `null` or a `kotlin.jvm.functions.Function1`.

## One run at a time, on the whole machine

A run takes the machine while it lasts, and since two runs sharing a host
measure each other, that guarantee reaches across processes as well as threads:
a second JVM waits for the first rather than competing with it.

```
proofload: waited 204.682774ms for the machine
proofload: checkout — 30,000 users over 10m, 2 steps each
```

A run that did not queue says nothing. The lock is a `FileLock` on a file under
`java.io.tmpdir`, so the OS releases it when a process dies, so a killed run frees
the machine for the next one with nothing to clean up.

It degrades rather than fails. A read-only temp directory, a container without
one, a filesystem that will not lock: each says so once and runs anyway, with
the guarantee back to one run at a time in this JVM. A load test that died
because it could not create a lock file is one people stop running.

Four properties, all optional:

| Property | Default | What it does |
|---|---|---|
| `proofload.exclusive` | `true` | `false` lets runs overlap. Set it on a benchmark, which is measuring the machine and must not queue behind a test. |
| `proofload.exclusive.file` | `$java.io.tmpdir/proofload-machine.lock` | Where the lock lives. One name per host is what makes two people queue for each other. |
| `proofload.exclusive.timeout` | none | Seconds to queue before giving up, failing with the holder's pid. Unset waits indefinitely. |
| `proofload.resolution` | measured | A floor somebody already measured, skipping `calibrate()`. A floor named rather than measured carries no probe, so nothing can compare two machines by it. |

Two containers on one host cannot serialise against each other this way: they
do not share a temp directory, and no file can fix that. Nothing here pretends
otherwise.

## A baseline in GitHub Actions

A baseline is a file. Comparing this run to the last one means having that file
on the runner, and GitHub gives you three places to keep it. Start with the
cache; the other two are for when you need a copy that outlives it or one a
human can look at.

### The job

One JVM invocation does the whole thing: run the simulation, compare it to
whatever baseline was restored beside it, write the comparison into the job
summary, and leave this run behind as the next one's baseline.

```kotlin
import io.github.matthewjones372.proofload.Change
import io.github.matthewjones372.proofload.Comparison
import io.github.matthewjones372.proofload.against
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.baseline.readBaseline
import io.github.matthewjones372.proofload.baseline.writeBaseline
import io.github.matthewjones372.proofload.calibratedBy
import io.github.matthewjones372.proofload.engine.Proofload
import io.github.matthewjones372.proofload.http.http
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.report.appendToStepSummary
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.step
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.minutes

val pay = step("pay")
val api = http.baseUrl("https://orders.internal")
val paying = scenario("paying") { exec(pay, api.get("/pay")) }

fun main() {
    val baseline = Path.of("build/proofload/paying.proofload")
    val proofload = Proofload()

    // Before the load, not after: this is what the machine could do while
    // nothing else was asked of it.
    val floor = proofload.calibrate()
    val result = proofload.run(paying.at(200.perSecond, over = 2.minutes)).calibratedBy(floor)

    val previous = baseline.takeIf { Files.exists(it) }?.let(::readBaseline)
    val comparison = result.against(previous)

    result.appendToStepSummary(comparison, floor)
    result.writeBaseline(baseline)

    // Failures fail the job. Latency does not — see below.
    if (result.failed > 0L) exitProcess(1)
}
```

```kotlin
// build.gradle.kts
dependencies {
    // `0.1.0-rc4` is the current release on Maven Central; anything newer is
    // what you built with ./gradlew publishToMavenLocal.
    implementation("io.github.matthewjones372:proofload-core:$proofloadVersion")
    implementation("io.github.matthewjones372:proofload-engine:$proofloadVersion")
    implementation("io.github.matthewjones372:proofload-http:$proofloadVersion")
    implementation("io.github.matthewjones372:proofload-baseline:$proofloadVersion")
    implementation("io.github.matthewjones372:proofload-report-github:$proofloadVersion")
}
```

`appendToStepSummary` writes to the file `GITHUB_STEP_SUMMARY` names, and does
nothing at all off Actions, so the same `main` runs on a laptop.

There is a working copy of this in the repository:
[`AgainstTheBaseline.kt`](../examples/src/main/kotlin/io/github/matthewjones372/proofload/examples/AgainstTheBaseline.kt),
run by [`baseline.yml`](../.github/workflows/baseline.yml) against a JDK
`HttpServer` in the same process.

### Keeping it: the cache

```yaml
- name: Restore the last baseline
  uses: actions/cache@v4
  with:
    path: build/proofload
    key: proofload-baseline-${{ github.run_id }}
    restore-keys: proofload-baseline-
```

The key is a run id, so it never hits and the cache is always written fresh at
the end of the job. The prefix `restore-keys` is what finds the newest one that
was written under any earlier run id. That pair is the whole trick: a key that
never matches and a prefix that always does gives you a rolling baseline rather
than one frozen the first time it was saved.

A pull request reads the cache its base branch wrote and writes only into its
own scope, so the comparison is against `main` and one branch cannot poison
another's. A cache nothing reads for seven days is evicted.

### Keeping it: an artifact

Eviction is the cache's failure mode, and a workflow that wants a baseline from
a different workflow cannot use a cache at all. Upload the file:

```yaml
- name: Keep the baseline where a later run can fetch it
  if: always()
  uses: actions/upload-artifact@v7
  with:
    name: proofload-baseline
    path: build/proofload/*.proofload
    retention-days: 90
    if-no-files-found: error
```

and fetch the newest successful one before the run:

```yaml
- name: Fall back to the last baseline main published
  env:
    GH_TOKEN: ${{ github.token }}
  run: |
    mkdir -p build/proofload
    run_id=$(gh run list --branch main --workflow baseline.yml --status success \
      --limit 1 --json databaseId --jq '.[0].databaseId')
    gh run download "$run_id" --name proofload-baseline --dir build/proofload \
      || echo "no baseline published yet; this run will write the first one"
```

The `||` matters. A missing baseline is a thing to report, not a thing to die
of, and Proofload reports it, so let the step pass and let the summary say it.

### Keeping it: a branch somebody reviews

A cache and an artifact are both invisible until something breaks. Where the
baseline is a number the team argues about, the rate a service is expected to
hold, put it on an orphan branch and change it in a pull request:

```bash
git switch --orphan proofload-baseline
git rm -rf .
cp build/proofload/paying.proofload .
git add paying.proofload && git commit -m "chore: baseline for paying at 200/s"
git push origin proofload-baseline
```

and read it in the job, which needs no checkout of it:

```yaml
- name: Fetch the reviewed baseline
  run: |
    mkdir -p build/proofload
    git fetch --depth 1 origin proofload-baseline
    git show FETCH_HEAD:paying.proofload > build/proofload/paying.proofload
```

Then stop writing it from the job: a baseline somebody reviews is one only a
pull request changes. Moving it is then a diff with a person's name on it,
which is the point.

## More than one run, and a verdict worth having

A single run cannot bound its own noise. One measurement of a target says
nothing about how far a second would land from it, so a comparison of one run
against one baseline can say what moved but not whether the move was real.

`Runs` is several runs of one plan, and `against` compares two populations by
resampling the runs that made them, so the spread it judges by is the observed
spread of the thing being compared, rather than a number borrowed from
somewhere else:

```kotlin
import io.github.matthewjones372.proofload.Runs
import io.github.matthewjones372.proofload.against
import io.github.matthewjones372.proofload.baseline.readAll
import io.github.matthewjones372.proofload.baseline.writeInto
import io.github.matthewjones372.proofload.junit5.assertNotWorseThan

val before = Runs.readAll(Path.of("baselines/checkout"))
val now = Runs(List(5) { proofload.run(checkout.at(50.perSecond, over = 2.minutes)) })

now.against(before, p99(placeOrder), acceptable = 10.percent)
    .assertNotWorseThan(10.percent)
```

In Kotest, the same threshold as a matcher:

```kotlin
import io.github.matthewjones372.proofload.kotest.NotWorseThan

now.against(before, p99(placeOrder)) should NotWorseThan(10.percent)
```

Five runs a side is the fewest it will make an interval out of; a bootstrap over
three values is arithmetic wearing a lab coat. Below that it refuses and says so.

The verdict is `Better`, `Worse`, or `CannotTell`, and the third is the feature.
A comparison that always answers better or worse is the last place in this tool
where an unmeasured number gets printed. `CannotTell` carries both why and what
would change it, because a refusal nobody can act on is one a team learns to
route around.

By default a `CannotTell` passes. A test that failed on a noisy Tuesday gets
deleted on the Wednesday. Ask for the stricter reading where you would rather
stop and look:

```kotlin
difference.assertNotWorseThan(10.percent, orCannotTell = true)
```

Each run is one file, so a shell loop that forks a JVM per run leaves a
directory `readAll` can pick up:

```bash
for i in $(seq 1 5); do ./gradlew :examples:soak; done   # each writes one file
```

```kotlin
result.writeInto(Path.of("baselines/checkout"))   // run-<started>-<pid>.proofload
```

Both halves of the name are needed: two runs of one JVM start at different
times, and two JVMs started together do not.

## More than one injector

When one JVM cannot send the load, `docs/what-it-costs.md` puts that wall
somewhere between ten and twenty-five thousand a second on four shared cores,
split the run across hosts. Each is given which one it is, how many there are,
and the instant they all start on:

```kotlin
proofload.run(soak.sharded(index = 2, of = 4, startingAt = at))
```

Injector *k* of *N* sends the users whose number is `k` modulo `N`, so the four
of them offer exactly the departures one JVM would. Each writes one file, and
the directory reads back as the run they were pieces of:

```kotlin
Shards.readAll(Path.of("run")).merged[pay].responseTime.p99
```

The launcher is a shell loop and ssh; the refusals, the worst injector's
lateness and what one host can and cannot stand in for are in
[more-than-one-injector.md](more-than-one-injector.md).

## What a statistic has been doing

A comparison is pairwise, and pairwise is blind to a creep by construction. A
p99 drifting two percent a point sits inside every interval, because a runner's own
run-to-run spread is about that, so `against` answers "cannot tell" forty
times while the number moves a third.

A trend is a directory of directories: one subdirectory per point, holding that
point's runs. The subdirectory name is the label, because the baseline file
carries nothing that says what it was a measurement *of*:

```bash
for i in $(seq 1 5); do ./gradlew :examples:soak; done   # into history/$GITHUB_SHA/
```

```kotlin
import io.github.matthewjones372.proofload.baseline.readTrend
import io.github.matthewjones372.proofload.report.writeHtmlReport

val trend = readTrend(Path.of("history"), p99(pay), acceptable = 5.percent)

trend.ends          // oldest point against newest — the one comparison a creep shows in
trend.steps         // the adjacent pairs whose own runs support a move
trend.comparisons   // how many were made, so the page can say so

trend.writeHtmlReport(Path.of("build/reports/trend.html"))
```

Nothing is fitted, so there is no slope to quote, and nothing is drawn between
points nobody ran. A change of machine is a break rather than a smoothed
segment: `Runs` refuses to merge unlike machines for the same reason, and a
pair that straddles one moved by an amount nothing here can separate from the
runner.

Read the count beside the steps. Thirty-nine adjacent comparisons at 95% expect
about two named steps in a series that never moved, so a named step is a place
to look rather than a finding. Widening every interval by the comparison count
was the alternative, and it would make 95% here mean something other than 95%
on the run report.

## gRPC

The caller runs protoc; this module never sees a `.proto`. You hand it the
descriptor your generated code already carries, and build your own stub on the
channel it gives you:

```kotlin
import io.github.matthewjones372.proofload.grpc.exec
import io.github.matthewjones372.proofload.grpc.grpc

val orders = grpc.target("orders.internal:8443").traced().deadline(2.seconds)
val stub = OrdersGrpc.newBlockingStub(orders.channel)

val checkout = scenario("checkout") {
    exec(orders.call(OrdersGrpc.getPlaceOrderMethod()) { stub.placeOrder(anvil) })
}
```

The row is named `orders.v1.Orders/PlaceOrder`, off the descriptor rather than
off a string you typed, so two call sites of one method are one row.

A status is a value the target sent, so it reads back by name:

```kotlin
result[placeOrder].failedWith(GrpcStatus(Status.Code.UNAVAILABLE))
result[placeOrder].failedWith(TimedOut)   // DEADLINE_EXCEEDED, under the one name every module uses
```

`deadline(...)` is the budget a call gets when it sets none of its own, a call
with no deadline waits as long as the target likes, which in a load test is a
user who never departs again. Your own `withDeadlineAfter` still wins.

**One channel for the run, not one per user.** A `ManagedChannel` is a
thread-safe pool, and one per user would measure TLS handshakes rather than the
target. The cost is gRPC's known trap: one channel resolves to one subchannel,
so a run can land on one backend with HTTP/2 capping concurrent streams. That
is the generator's ceiling rather than the target's; read `behind` and the injector's
own limits before believing a gRPC number.

**No transport here.** `grpc-netty-shaded` or `grpc-okhttp` is your choice and
already on your classpath with your stubs; `forTarget` finds it.

### A stream

```kotlin
open(orders.stream(OrdersGrpc.getChatMethod()) { answers -> stub.chat(answers) })
repeat(100) { send(each, message) }
awaiting(answers, count = 100, within = 30.seconds)
```

`awaiting(count = 100)` is a hundred samples under one name, each timed from
the message it answers, not one sample covering a hundred messages and the
gaps between them. An answer arriving with nothing outstanding is counted as
unsolicited and not timed: there is no departure to measure it from.

### A server stream

A server-streaming call sends one request and reads many answers, none of which
answers a send of its own. There are two honest readings of that and they are
not the same number, so there are two verbs:

```kotlin
val fills = orders.serverStream(OrdersGrpc.getWatchFillsMethod()) { request, answers ->
    stub.watchFills(request, answers)
}

val watching = scenario("watching") {
    open(fills, request = symbol)
    firstAnswer(opened, within = 5.seconds)
    cadence(ticks, count = 99, within = 60.seconds)
}
```

`firstAnswer` is one sample, from the call being opened to the first message
arriving: a round trip, comparable with a unary call's latency. `cadence` is
one sample per message after that, each measured from the message before it:
the rate the target actually delivered at. A hundred messages is `firstAnswer`
plus `cadence(count = 99)`.

They are kept apart on purpose. Timed from the call, the *k*th message climbs
with *k* and the histogram fills with a number that describes the stream's
length rather than the target's speed; put in with the round trip, the two
average into a figure describing neither. So a `cadence` before any
`firstAnswer` fails with `NoFirstAnswer` rather than handing the round trip out
as the first gap, the one mistake nobody reading the report could catch.

`send`, `awaiting` and `done` fail with `NotSending` here: the one request went
out with the call.

## Database steps

The database under a service is where the ceiling usually is, and from the HTTP
side it is invisible except as latency nobody can attribute. `proofload-jdbc`
sends statements over a `DataSource` you hand in:

```kotlin
import io.github.matthewjones372.proofload.jdbc.exec
import io.github.matthewjones372.proofload.jdbc.jdbc
import io.github.matthewjones372.proofload.jdbc.rows
import io.github.matthewjones372.proofload.jdbc.waitedForPool
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.sessionKey
import io.github.matthewjones372.proofload.step

val orderId = sessionKey<Int>("orderId")
val byId = step("select an order")

val orders = jdbc.on(dataSource)

val reading = scenario("reading") {
    exec(byId, orders.query("select id, sku from orders where id = ?").binding { listOf(it[orderId]) })
}
```

```groovy
dependencies {
    testImplementation("io.github.matthewjones372:proofload-jdbc:0.1.0-rc4")
    // The driver and the pool are yours, and are the point: this module carries
    // neither, so a run measures the pool your service actually runs.
    testImplementation("org.postgresql:postgresql:42.7.4")
    testImplementation("com.zaxxer:HikariCP:6.2.1")
}
```

**The pool wait is its own number, and it is what the module is for.**

```kotlin
result[byId].serviceTime.p99   // what the database took
result[byId].waitedForPool.p99 // what your users spent queueing for a connection
result[byId].rows              // rows the query returned, counted
```

A hand-written `exec(name) { }` with your own JDBC call inside times the
checkout and the query together and calls the total the database's latency.
That is `behind` all over again, one layer down: the generator's own queueing
reported as the target's speed. With them apart, a report can say *the database
answered in 3 ms and your users waited 400 ms for a connection*, which is a
different bug with a different fix, and the one a team is more often actually
hitting. A pool that saturates at 40 connections is the ceiling the whole
service hits.

`binding { }` reads the session, so a run is not ten thousand identical selects
measuring a query cache. It takes the parameters in the order the `?`s appear.

**Rows are counted, never read into objects.** `next()` in a loop with nothing
in the body is the honest measurement of "the database sent this much"; anything
more measures the driver's object mapping. A caller who needs a value uses
`exec(name) { }` and their own client, exactly as they do today.

**A failure is a SQLSTATE, not a stack trace.** A deadlock, a unique violation
and a syntax error are three rows in a report under `SqlState`, and one row
saying `SQLException` under a class name. A driver that names no SQLSTATE gets
`Threw(class)`, and a driver-declared timeout gets `TimedOut`.

**A word about carrier pinning.** JDBC is blocking, and each user runs on its
own virtual thread. JDK 21 pins the carrier for the duration of a `synchronized`
block, which several drivers still use on their hot paths; a run that pins is a
run whose concurrency is capped at the carrier pool rather than at the database.
The PostgreSQL driver from 42.7 and the newer MariaDB and MySQL drivers unmount
cleanly; older ones and several commercial drivers do not. Read the injector's
own limits on the page ([0065](../specs/0065-the-injectors-own-limits.md)) before
believing a ceiling found this way, and run with
`-Djdk.tracePinnedThreads=short` once to see whether yours pins.

**No transactions across steps, and no batches yet.** A connection held across a
step is a connection held across a think time, which is a pool exhausted by a
scenario rather than by load.

## Kafka, and the answer on another topic

A broker acking a produce is not a consumer having done the work, and a team
load-testing Kafka almost always wants the second. So a produce is an `emit`
and the answer is a `completing`, the shape [work that finishes somewhere
else](#work-that-finishes-somewhere-else) already describes:

```kotlin
import io.github.matthewjones372.proofload.kafka.Header
import io.github.matthewjones372.proofload.kafka.completions
import io.github.matthewjones372.proofload.kafka.emit
import io.github.matthewjones372.proofload.kafka.kafka

val broker = kafka.brokers("localhost:9092").acks(Acks.All)

val trades = scenario("trades") {
    emit(
        submitted,
        broker.topic("trades")
            .keyed { it[account]?.toString()?.toByteArray() }
            .value { avro.serialize(it[trade]) }        // your serializer
            .correlatedBy(Header("trade-id")),
        keyedBy = { it[account] ?: 0L },
    )
}

trades.at(5_000.perSecond, over = 5.minutes)
    .completing(
        settled,
        from = broker.topic("settlements").correlatedBy(Header("trade-id")).completions(),
        drainingFor = 30.seconds,
    )
```

The correlation is stated once, at the `emit`, and goes to both the departure
the run counts and the header the record carries. The completion side reads
that header and never touches the payload, which is why it needs no
deserializer, and why no schema registry is involved in reading an answer.

`submitted` times the broker's ack, which is `acks` deep: an in-sync-replica
round trip at `Acks.All` and nothing at all at `Acks.None`. `settled` is the
number that matters, measured from the departure the profile promised, not
from when the record reached the broker.

**No serializer here, and no registry.** `io.confluent:kafka-avro-serializer`
is not on Maven Central, so depending on it would force a
`packages.confluent.io` declaration on everyone who took this module. Your
serializer is a `(Session) -> ByteArray?` lambda and this module never looks
inside it.

### What a schema lookup costs, and where it shows

A registry-backed serializer fetches a schema once per subject and caches it,
so the first record pays an HTTP round trip. Because the cache is shared, any
record departing while that fetch is in flight waits behind it.

That cost lands in **the produce step's own latency**: the lambda runs inside
the step body, so it is timed as that step. It does *not* land in `behind`:
every user runs on a thread of its own, so one blocked in a serializer holds up
nobody else's departure. Reading that first spike as the broker being slow is
the mistake to avoid.

Read `result.steady` for the run without it; that is what the steady segment
is for.

### Two more things worth knowing

`linger.ms` is set to 0 unless you say otherwise. A producer that lingers turns
an evenly spaced departure stream into bursts at the broker, so the arrivals
figure would report a smoothness the broker never saw. If you raise it, that
figure describes the injector rather than the broker.

When the accumulator fills, `send` blocks up to `max.block.ms` on the calling
thread. That is real backpressure and it is reported honestly, but it arrives
as generator lateness in `behind`, not as target latency, so read [did the
generator keep up?](#did-the-generator-keep-up) before concluding the cluster
is fine.

## Send the numbers somewhere else

A run's measurements in formats other tools read: an HdrHistogram log, a
Prometheus or OpenMetrics exposition, and an OTLP push at a collector.

```kotlin
import io.github.matthewjones372.proofload.export.writeHistogramLog
import io.github.matthewjones372.proofload.export.writeOpenMetrics
import io.github.matthewjones372.proofload.otel.sendOtlp

result.writeHistogramLog(Path.of("build/proofload/run.hlog"))
result.writeOpenMetrics(Path.of("/var/lib/node_exporter/proofload.prom"))
result.sendOtlp("http://collector:4318/v1/metrics")
```

All three read a frozen result after the run: nothing is scraped while requests
are departing. All three carry the measurements, the latencies, the run's own
lateness, the injector's stalls, the failures by reason, and none of them
carries the judgement, which stays where the sentence next to it survives.

Read [exporting.md](exporting.md) before writing a query against them. The
short version: these are the histogram's own buckets, so `histogram_quantile()`
interpolating inside one gives you something the numbers do not support, and
there is no `_sum` to make a mean out of.

## Publish the reports to GitHub Pages

The HTML report is one self-contained file, so publishing is a copy and an
index:

```kotlin
import io.github.matthewjones372.proofload.report.writePagesIndex

val directory = Path.of("build/pages")
result.writeHtmlReport(directory.resolve("checkout.html"))
capacity.writeHtmlReport(directory.resolve("capacity.html"))

writePagesIndex(directory)   // an index.html linking whatever is there
```

```yaml
- uses: actions/upload-pages-artifact@v3
  with:
    path: build/pages
- uses: actions/deploy-pages@v4
```

The index is generated from the directory rather than from a list you maintain,
so a report that stops being written stops being linked.

There is also the job summary, which needs no Pages setup at all:

```kotlin
import io.github.matthewjones372.proofload.report.appendToStepSummary

result.appendToStepSummary(comparison = comparison, floor = floor)
```

Off Actions that writes nothing and answers `StepSummary.NotOnActions` rather
than throwing. The same call runs on a laptop, and a load test that dies because
it is not in CI is one people stop running locally.

## Bound what a run on this machine may do

A rate arrives from somewhere: a person, a script, or a plan file a program
wrote. `proofload.toml` beside the build says what this machine permits, and
`preview` says what a plan would do before it does any of it:

```kotlin
import io.github.matthewjones372.proofload.Allowance
import io.github.matthewjones372.proofload.Preview
import io.github.matthewjones372.proofload.preview

when (val asked = checkout.at(50.perSecond, over = 1.minutes).preview(Allowance.fromFile())) {
    is Preview.Allowed -> println("${asked.users} users to ${asked.hosts}")
    is Preview.Refused -> println(asked.reason.described)
}
```

`Proofload().runWithin(allowance, plan)` returns `Ran.Refused` instead of
departing. It is a fence rather than a sandbox, and
[allowance.md](allowance.md) says where that stops.

## Do not gate a merge on latency

On a shared runner, gate on failures and errors. Do not gate on latency.

GitHub-hosted runners are shared machines. The same code, the same commit and
the same rate measure differently between two runs of them, and this repository's
own benchmark shows p99 tails dominated by machine stalls rather than by load. A
threshold on p99 therefore fails some fraction of pull requests for reasons
nobody can act on, and a gate that fails a third of the time gets deleted
within a month, taking the failure and error checks with it.

So:

- **Fail the job** on failed requests, on errors, and on a step that stopped
  running at all. Those are the same on any machine.
- **Report** latency. The job summary carries the comparison; a reviewer reads
  it and decides.
- If you want a latency gate, run it on a machine you own, where the same code
  measures the same twice, and keep it out of the merge path.

## What the comparison will refuse to say

`against` answers `Comparison.NotComparable` rather than inventing a delta:

| It says | When |
|---|---|
| `no baseline to compare against` | the cache missed, or this is the first run |
| `these runs were not asked to do the same thing` | a different scenario, steps, rate line, or drawn data both runs named |

and `Comparison.Compared` carries a `caveat` where the two runs are comparable
but something about the machines argues against the numbers. A calibration
probe (the same target-free measurement on both machines) is what turns "this
might be the runner" into `slowdown: 2.0`. Past a quarter slower the caveat
leads with it, above any step, because the runner is then the likelier
explanation of everything under it.

Two probes are the same work timed twice, which is the one cross-machine
comparison that is like for like. It is not `resolution`, which is the *spread*
of those repeats as a fraction of a null step's own tiny median.

## What calibration costs

`calibrate()` is bounded at thirty seconds, measured once per JVM and kept. On a
runner whose spread you have already measured, name it instead:

```yaml
- run: ./gradlew :examples:againstTheBaseline -Dproofload.resolution=0.05
```

That skips the measurement, and with it the probe: a floor somebody typed has
no probe behind it, so nothing can compare the runner to the baseline's. Skip
it where the runners are identical and known; measure it where they are not,
which on hosted runners is most of the time.
