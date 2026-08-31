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

## Contents

**Getting a run out of it** — [a first load test](#a-first-load-test) ·
[the same thing in Kotest](#the-same-thing-in-kotest) ·
[without a test framework](#without-a-test-framework) ·
[see what a scenario does before running it](#see-what-a-scenario-does-before-running-it) ·
[know how long it will take](#know-how-long-it-will-take) ·
[quieten the progress lines](#quieten-the-progress-lines)

**Shaping the load** — [flat, ramped, and staged](#flat-ramped-and-staged) ·
[stop sending on a metronome](#stop-sending-on-a-metronome) ·
[think time](#think-time) ·
[loops and conditions](#loops-and-conditions) ·
[two journeys in one run](#two-journeys-in-one-run)

**Giving users their own data** — [a function of the user number](#a-function-of-the-user-number) ·
[a fixed list](#a-fixed-list) · [a CSV file](#a-csv-file) ·
[a token that expires mid-run](#a-token-that-expires-mid-run)

**The requests themselves** — [chain two steps with a capture](#chain-two-steps-with-a-capture) ·
[check the body, not just the status](#check-the-body-not-just-the-status) ·
[sign in once and carry the cookie](#sign-in-once-and-carry-the-cookie) ·
[follow a slow request into your traces](#follow-a-slow-request-into-your-traces) ·
[a step that is not HTTP](#a-step-that-is-not-http) ·
[WebSockets](#websockets) ·
[work that finishes somewhere else](#work-that-finishes-somewhere-else)

**Asking the question** — [assert, or declare goals](#assert-or-declare-goals) ·
[ask what actually failed](#ask-what-actually-failed) ·
[read only the part that settled](#read-only-the-part-that-settled) ·
[find the rate it sustains](#find-the-rate-it-sustains) ·
[did the generator keep up?](#did-the-generator-keep-up)

**Keeping the answer** — [write an HTML report](#write-an-html-report) ·
[a baseline in GitHub Actions](#a-baseline-in-github-actions) ·
[more than one run, and a verdict worth having](#more-than-one-run-and-a-verdict-worth-having) ·
[publish the reports to GitHub Pages](#publish-the-reports-to-github-pages) ·
[do not gate a merge on latency](#do-not-gate-a-merge-on-latency) ·
[what the comparison will refuse to say](#what-the-comparison-will-refuse-to-say) ·
[what calibration costs](#what-calibration-costs)

---

## A first load test

`@LoadTest` is `@Test` plus a `Kestrel` parameter. There is nothing to
register, no base class, and no lifecycle to remember.

```kotlin
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.engine.Kestrel
import io.github.matthewjones372.kestrel.http.exec
import io.github.matthewjones372.kestrel.junit5.LoadTest
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.scenario
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class CheckoutLoadTest {

    @LoadTest
    fun `checkout holds up at fifty a second`(kestrel: Kestrel) {
        val checkout = scenario("checkout") {
            exec(browse, api.get("/products"))
            exec(placeOrder, api.post("/orders").body("""{"cart":"1 anvil"}""").expecting(201))
        }

        val result = kestrel.run(checkout.at(50.perSecond, over = 1.minutes))

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

`kestrel()` is a suspend function that hands back the runner for the test it is
called from. No spec base class, and nothing to register — called from a spec
that registered nothing it still works.

```kotlin
import io.github.matthewjones372.kestrel.kotest.kestrel
import io.kotest.core.spec.style.StringSpec

class CheckoutSpec : StringSpec({

    "checkout holds up at fifty a second" {
        val result = kestrel().run(checkout.at(50.perSecond, over = 1.minutes))

        result[placeOrder].responseTime.p99 shouldBeLessThan 200.milliseconds
    }
})
```

## Without a test framework

A simulation is a value and `run` is an extension on it, so a `main` is enough.
This is the shape for a scheduled soak or a one-off from a shell.

```kotlin
import io.github.matthewjones372.kestrel.engine.run
import io.github.matthewjones372.kestrel.report.writeHtmlReport
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
import io.github.matthewjones372.kestrel.engine.trace

checkout.trace()
```

```
kestrel: trace checkout
kestrel:   /products     ok
kestrel:   /orders       FAILED status 500 — user abandoned here
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
kestrel: checkout — 30,000 users over 10m, 2 steps each
kestrel: 00:05  departed 250  in flight 3  behind 88.033us  09:55 left
kestrel: 10:00  departed 30,000  in flight 41  behind 96.718us  draining
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
kestrel: capacity — at most 10 rungs of 2s and the bisection after them, so at most 30s
kestrel: rung 1 — 4/s passed, at most 28s left
kestrel: rung 5 — 20/s failed, at most 20s left
kestrel: rung 9 — 17/s passed, at most 12s left
```

"At most", never a forecast. A bound that is beaten leaves you pleasantly
surprised; a forecast that is missed is a tool that lied. A rung that voids says
so and stops the bound, because the generator lost ground and the answer is then
about this machine rather than about the target.

## Quieten the progress lines

When the output is somebody else's report — a CI step that parses stdout, a test
framework — hand it a quiet one:

```kotlin
import io.github.matthewjones372.kestrel.Progress

val kestrel = Kestrel(Progress.silent)

// or a different interval
val kestrel = Kestrel(Progress.lines(every = 30.seconds))
```

Nothing in a tick is read from what was recorded. A live histogram read for a
percentile would put the watcher's work on the path being timed, so these are
numbers the scheduler already keeps: a run costs the same watched as unwatched.

## Flat, ramped, and staged

`at` is the flat case and covers most tests. For anything else, name the shape:

```kotlin
import io.github.matthewjones372.kestrel.constantRate
import io.github.matthewjones372.kestrel.hold
import io.github.matthewjones372.kestrel.injecting
import io.github.matthewjones372.kestrel.rampRate
import io.github.matthewjones372.kestrel.then
import io.github.matthewjones372.kestrel.thenRampTo

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

## Stop sending on a metronome

Evenly spaced departures understate queueing at the rate they claim to be
testing: real arrivals are close to Poisson, and queueing delay scales with the
variability of arrivals rather than only with their mean.

```kotlin
import io.github.matthewjones372.kestrel.randomized

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
finishes its iteration and extends the run — the alternative is cutting a user
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
import io.github.matthewjones372.kestrel.Arm
import io.github.matthewjones372.kestrel.Simulation

val simulation = Simulation(
    arms = listOf(
        Arm(browsing, constantRate(500.perSecond, over = 10.minutes)),
        Arm(checkout, constantRate(20.perSecond, over = 10.minutes)),
    ),
)

kestrel.run(simulation)
```

The arms are merged into one departure schedule rather than booked one after
another — a run that sent all of one arm and then all of the next would hand the
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
import io.github.matthewjones372.kestrel.feed
import io.github.matthewjones372.kestrel.fedBy
import io.github.matthewjones372.kestrel.sessionKey

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

## A fixed list

```kotlin
import io.github.matthewjones372.kestrel.feedFrom

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
import io.github.matthewjones372.kestrel.csv
import io.github.matthewjones372.kestrel.feeding

val customer = sessionKey<String>("customer")
val tier = sessionKey<String>("tier")

val accounts = csv(Path.of("src/test/resources/accounts.csv"))

checkout.at(50.perSecond, over = 1.minutes).fedBy(accounts.feeding(customer, tier))
```

Each key fills from the column the header gave the same name, so there is no
second place to keep the mapping. A key naming a column the file does not have
fails when the feeder is built, naming the column and listing the ones that are
there — before the run departs anything, rather than on user one.

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
index into a list — nothing allocated, nothing locked, and it wraps round rather
than running out. The grammar is a deliberately small part of RFC 4180 — quoted
fields and doubled quotes inside them — because core carries no dependencies and
anything wider is a CSV library.

## A token that expires mid-run

Fetching a token inside a step puts that round trip in one request out of a few
hundred, and the p99 becomes a measurement of your identity provider.
`refreshing` keeps it off the measured path:

```kotlin
import io.github.matthewjones372.kestrel.refreshing

val token = refreshing(every = 4.minutes) { fetchToken() }

val checkout = scenario("checkout") {
    exec(placeOrder) {
        send(api.post("/orders").header("authorization", "Bearer ${token.current}"))
    }
}

// afterwards
token.stop()
```

The first fetch happens when `refreshing` is called — before the run, so it
cannot land inside whichever virtual user reads it first. Every later fetch runs
on a daemon thread no departure is submitted to. `current` is a volatile read of
a value already in hand and allocates nothing.

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
declared on rather than the step that would have used the value — which is where
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
`java.net.CookieHandler` hangs off the client — so a jar there would be one jar
shared by fifty thousand users taking turns being one logged-in person.

Name and value only: no expiry, no path or domain matching, because a load test
sends to one base URL. It is off unless asked for.

Redirects are off unless asked for too — a 302 is the failure it was, under the
step that got it — until `following()` says otherwise:

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
by the same route. The first reason wins — a timeout that follows a 503 is the
503's doing, and renaming it loses which one to go and fix.

## WebSockets

Two handshakes, each its own timed step:

```kotlin
import io.github.matthewjones372.kestrel.websocket.close
import io.github.matthewjones372.kestrel.websocket.open
import io.github.matthewjones372.kestrel.websocket.ws

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

`open` times the upgrade alone — from the request leaving to the server's 101
completing it. It is not a first message and not the first byte of one: nothing
here says when data starts to flow.

`close` times what the target took to let go, not what the write took: the sample
runs from the Close frame being written to the far end's Close arriving back.

Between them, `send` and `awaiting` are two steps because they are two
questions:

```kotlin
import io.github.matthewjones372.kestrel.Correlation
import io.github.matthewjones372.kestrel.websocket.awaiting
import io.github.matthewjones372.kestrel.websocket.send

val subscribe = step("subscribe")
val ticks = step("ticks")

val streaming = scenario("streaming") {
    open(connect, feed.at("/prices"))
    send(subscribe, feed.text("SUB ACME"), keyedBy = Correlation { it[order] ?: 0L })
    awaiting(ticks, count = 100, within = 30.seconds)
    close(disconnect)
}
```

`send` is timed for the write alone and waits for nothing — the sample ends when
the client reports the frame written. `awaiting` is timed for the wait alone,
from the step being reached to the last of `count` answers arriving. Keeping
them apart is what stops a slow target being reported as a slow write.

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

One caveat worth knowing: `awaiting` records **one** sample for the whole batch
rather than one per message, because a step can produce only one sample today.
For a per-message distribution, wait for one at a time.

One socket per user, which is the opposite of the shared HTTP client and for the
opposite reason: a stream test is about how many connections a target holds, so
amortising the handshake would remove the thing being measured. Expect file
descriptors to bound your user count long before the scheduler does.

## Work that finishes somewhere else

Some systems answer on another topic, another queue, or a webhook. The latency
that matters is not the ack — it is the departure the profile promised to the
answer arriving.

```kotlin
import io.github.matthewjones372.kestrel.Correlation
import io.github.matthewjones372.kestrel.InMemoryCompletions
import io.github.matthewjones372.kestrel.completing

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

val result = kestrel.run(
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
import io.github.matthewjones372.kestrel.expecting
import io.github.matthewjones372.kestrel.failureRate
import io.github.matthewjones372.kestrel.goodput
import io.github.matthewjones372.kestrel.keptSchedule
import io.github.matthewjones372.kestrel.p99
import io.github.matthewjones372.kestrel.percent

val result = kestrel.run(
    checkout.at(50.perSecond, over = 10.minutes).expecting(
        p99(placeOrder) under 200.milliseconds,
        failureRate under 0.1.percent,
        goodput(placeOrder, under = 200.milliseconds) atLeast 99.percent,
        keptSchedule,
    ),
)
```

Each goal becomes a `Verdict` carrying what was measured and, where it missed,
by what share of the target — "51% over" reads the same whether the limit was
200 ms or two seconds, and tells you whether you are looking at tuning or at
design.

`goodput` is worth reaching for over a bare percentile: it is the share of
requests that both succeeded and came back in time, so a target that gets fast
by failing does not pass it.

`keptSchedule` is the one that guards the rest. It asks whether the *generator*
kept to its own schedule; where it did not, every latency underneath includes a
queue this tool made, and the numbers are describing the injector.

## Ask what actually failed

A reason is a value, not a string, and the module that made the request is the
one that names it:

```kotlin
import io.github.matthewjones372.kestrel.Said
import io.github.matthewjones372.kestrel.Threw
import io.github.matthewjones372.kestrel.TimedOut
import io.github.matthewjones372.kestrel.http.CheckFailed
import io.github.matthewjones372.kestrel.http.HttpStatus
import io.github.matthewjones372.kestrel.http.NothingCaptured

result[placeOrder].failedWith(HttpStatus(503))        // the target said no
result[placeOrder].failedWith(TimedOut)               // it was up and too slow
result[placeOrder].failedWith(Threw("ConnectException"))
result[placeOrder].failedWith(CheckFailed("has an order id"))
```

Which is the point of the type — a string could be counted and nothing else:

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
works, and it must be a value — a data class or an object — because a reason is
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
import io.github.matthewjones372.kestrel.steady
import io.github.matthewjones372.kestrel.steadyState

result.steadyState        // SteadyState.From(offset) or NeverSettled
result.steady[placeOrder].responseTime.p99
```

Nothing is discarded by default: `result` still holds the whole run, and
`steady` gives back the whole run where it never settled rather than an empty
one. A goal that reads something a second does not keep — the generator's own
backlog, a failure reason — is judged over the whole run instead, because
narrowing it to a segment nothing measured would be inventing the answer.

## Find the rate it sustains

Rather than guessing a rate and asserting about it, ask for the highest one that
holds your goals:

```kotlin
import io.github.matthewjones372.kestrel.engine.run
import io.github.matthewjones372.kestrel.sustainable

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

## Did the generator keep up?

The first question to ask of any result, and the one most tools do not answer:

```kotlin
result.fellBehind()   // the generator did not keep its own schedule
result.lostGround()   // and the backlog was still growing at the end
result.behind.p99     // how late the late departures were
```

Where `fellBehind()` is true, every latency in the run includes time spent
waiting in a queue this tool created — coordinated omission, which is the
default bug in a load generator rather than an exotic one. The fix is a lower
rate or a bigger machine, not a closer reading of the percentiles.

`arrivals` says the same thing from the other side: the spacing the run actually
produced, and its coefficient of variation.

## Write an HTML report

One self-contained file — the data, the stylesheet and the script inline — so it
opens from a `file://` URL and uploads as a CI artifact unchanged.

```kotlin
import io.github.matthewjones372.kestrel.report.writeHtmlReport

result.writeHtmlReport(Path.of("build/reports/kestrel/checkout.html"))
```

```
kestrel: report written to build/reports/kestrel/checkout.html
kestrel:   file:///home/you/project/build/reports/kestrel/checkout.html
```

It takes the optional arguments that make it say more, and each one adds a
section rather than changing a number:

```kotlin
result.writeHtmlReport(
    path,
    comparison = result.against(readBaseline(path)),   // this run against the last
    floor = kestrel.calibrate(),                       // what this machine can resolve
    differences = listOf(difference),                  // several runs a side
)
```

A capacity search has a page of its own, with the curve on it:

```kotlin
capacity.writeHtmlReport(Path.of("build/reports/kestrel/capacity.html"))
```

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
import io.github.matthewjones372.kestrel.Change
import io.github.matthewjones372.kestrel.Comparison
import io.github.matthewjones372.kestrel.against
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.baseline.readBaseline
import io.github.matthewjones372.kestrel.baseline.writeBaseline
import io.github.matthewjones372.kestrel.calibratedBy
import io.github.matthewjones372.kestrel.engine.Kestrel
import io.github.matthewjones372.kestrel.http.http
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.report.appendToStepSummary
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.step
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.minutes

val pay = step("pay")
val api = http.baseUrl("https://orders.internal")
val paying = scenario("paying") { exec(pay, api.get("/pay")) }

fun main() {
    val baseline = Path.of("build/kestrel/paying.kestrel")
    val kestrel = Kestrel()

    // Before the load, not after: this is what the machine could do while
    // nothing else was asked of it.
    val floor = kestrel.calibrate()
    val result = kestrel.run(paying.at(200.perSecond, over = 2.minutes)).calibratedBy(floor)

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
    // Whatever you have published or built with ./gradlew publishToMavenLocal;
    // nothing is on Maven Central yet.
    implementation("io.github.matthewjones372:kestrel-core:$kestrelVersion")
    implementation("io.github.matthewjones372:kestrel-engine:$kestrelVersion")
    implementation("io.github.matthewjones372:kestrel-http:$kestrelVersion")
    implementation("io.github.matthewjones372:kestrel-baseline:$kestrelVersion")
    implementation("io.github.matthewjones372:kestrel-report-github:$kestrelVersion")
}
```

`appendToStepSummary` writes to the file `GITHUB_STEP_SUMMARY` names, and does
nothing at all off Actions, so the same `main` runs on a laptop.

There is a working copy of this in the repository:
[`AgainstTheBaseline.kt`](../examples/src/main/kotlin/io/github/matthewjones372/kestrel/examples/AgainstTheBaseline.kt),
run by [`baseline.yml`](../.github/workflows/baseline.yml) against a JDK
`HttpServer` in the same process.

### Keeping it: the cache

```yaml
- name: Restore the last baseline
  uses: actions/cache@v4
  with:
    path: build/kestrel
    key: kestrel-baseline-${{ github.run_id }}
    restore-keys: kestrel-baseline-
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
    name: kestrel-baseline
    path: build/kestrel/*.kestrel
    retention-days: 90
    if-no-files-found: error
```

and fetch the newest successful one before the run:

```yaml
- name: Fall back to the last baseline main published
  env:
    GH_TOKEN: ${{ github.token }}
  run: |
    mkdir -p build/kestrel
    run_id=$(gh run list --branch main --workflow baseline.yml --status success \
      --limit 1 --json databaseId --jq '.[0].databaseId')
    gh run download "$run_id" --name kestrel-baseline --dir build/kestrel \
      || echo "no baseline published yet; this run will write the first one"
```

The `||` matters. A missing baseline is a thing to report, not a thing to die
of — and Kestrel reports it, so let the step pass and let the summary say it.

### Keeping it: a branch somebody reviews

A cache and an artifact are both invisible until something breaks. Where the
baseline is a number the team argues about — the rate a service is expected to
hold — put it on an orphan branch and change it in a pull request:

```bash
git switch --orphan kestrel-baseline
git rm -rf .
cp build/kestrel/paying.kestrel .
git add paying.kestrel && git commit -m "chore: baseline for paying at 200/s"
git push origin kestrel-baseline
```

and read it in the job, which needs no checkout of it:

```yaml
- name: Fetch the reviewed baseline
  run: |
    mkdir -p build/kestrel
    git fetch --depth 1 origin kestrel-baseline
    git show FETCH_HEAD:paying.kestrel > build/kestrel/paying.kestrel
```

Then stop writing it from the job: a baseline somebody reviews is one only a
pull request changes. Moving it is then a diff with a person's name on it,
which is the point.

## More than one run, and a verdict worth having

A single run cannot bound its own noise. One measurement of a target says
nothing about how far a second would land from it, so a comparison of one run
against one baseline can say what moved but not whether the move was real.

`Runs` is several runs of one plan, and `against` compares two populations by
resampling the runs that made them — so the spread it judges by is the observed
spread of the thing being compared, rather than a number borrowed from
somewhere else:

```kotlin
import io.github.matthewjones372.kestrel.Runs
import io.github.matthewjones372.kestrel.against
import io.github.matthewjones372.kestrel.baseline.readAll
import io.github.matthewjones372.kestrel.baseline.writeInto
import io.github.matthewjones372.kestrel.junit5.assertNotWorseThan

val before = Runs.readAll(Path.of("baselines/checkout"))
val now = Runs(List(5) { kestrel.run(checkout.at(50.perSecond, over = 2.minutes)) })

now.against(before, p99(placeOrder), acceptable = 10.percent)
    .assertNotWorseThan(10.percent)
```

In Kotest, the same threshold as a matcher:

```kotlin
import io.github.matthewjones372.kestrel.kotest.NotWorseThan

now.against(before, p99(placeOrder)) should NotWorseThan(10.percent)
```

Five runs a side is the fewest it will make an interval out of; a bootstrap over
three values is arithmetic wearing a lab coat. Below that it refuses and says so.

The verdict is `Better`, `Worse`, or `CannotTell` — and the third is the feature.
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
result.writeInto(Path.of("baselines/checkout"))   // run-<started>-<pid>.kestrel
```

Both halves of the name are needed: two runs of one JVM start at different
times, and two JVMs started together do not.

## Publish the reports to GitHub Pages

The HTML report is one self-contained file, so publishing is a copy and an
index:

```kotlin
import io.github.matthewjones372.kestrel.report.writePagesIndex

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
import io.github.matthewjones372.kestrel.report.appendToStepSummary

result.appendToStepSummary(comparison = comparison, floor = floor)
```

Off Actions that writes nothing and answers `StepSummary.NotOnActions` rather
than throwing. The same call runs on a laptop, and a load test that dies because
it is not in CI is one people stop running locally.

## Do not gate a merge on latency

On a shared runner, gate on failures and errors. Do not gate on latency.

GitHub-hosted runners are shared machines. The same code, the same commit and
the same rate measure differently between two runs of them, and this repository's
own benchmark shows p99 tails dominated by machine stalls rather than by load. A
threshold on p99 therefore fails some fraction of pull requests for reasons
nobody can act on — and a gate that fails a third of the time gets deleted
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
| `these runs were not asked to do the same thing` | a different scenario, steps, or rate line |

and `Comparison.Compared` carries a `caveat` where the two runs are comparable
but something about the machines argues against the numbers. A calibration
probe — the same target-free measurement on both machines — is what turns "this
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
- run: ./gradlew :examples:againstTheBaseline -Dkestrel.resolution=0.05
```

That skips the measurement, and with it the probe — a floor somebody typed has
no probe behind it, so nothing can compare the runner to the baseline's. Skip
it where the runners are identical and known; measure it where they are not,
which on hosted runners is most of the time.
