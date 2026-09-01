<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/kestrel-mark-dark.svg">
  <img src="docs/assets/kestrel-mark-light.svg" width="88" height="88" alt="">
</picture>

# Kestrel

### Load testing for Kotlin, with numbers you can actually trust.

</div>

Your load test says p99 is 200 ms and you ship. But when a load generator can't
keep up, it quietly queues requests and then reports the wait as *your server's*
latency. So the test passes, the tail looks fine, and the slow service goes to
production anyway. This is **coordinated omission** — the classic bug in load
testing, and most tools can't even tell you it happened.

Kestrel is built around not doing that. It times every request from the moment
it was *supposed* to start, and every report says plainly whether the generator
kept up. If it didn't, you know the numbers are suspect. If it did, you can trust
them.

<div align="center">
<a href="docs/assets/report-full-light.png">
<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/report-verdict-dark.png">
  <img src="docs/assets/report-verdict-light.png" width="820" alt="A Kestrel run report: 2 of 4 goals met, with each goal's measurement beside it.">
</picture>
</a>

<sub>A real run. <code>POST /pay</code> missed its 300 ms target — and the green
<b>"the generator keeps its schedule"</b> is the proof that's the server's fault,
not the tool's. <a href="docs/assets/report-full-light.png">See the whole page →</a></sub>
</div>

## Write it in Kotlin, not in a framework

No base class to extend. No string keys to keep in sync. No XML, no separate DSL
language, no Docker. A scenario is a plain Kotlin value, and the result is
something your test reads straight off:

```kotlin
val orderId = sessionKey<String>("orderId")
val placeOrder = step("place order")
val api = http.baseUrl("https://orders.internal")

val checkout = scenario("checkout") {
    exec(step("browse"), api.get("/products"))
    exec(
        placeOrder,
        api.post("/orders")
            .body("""{"cart":"1 anvil"}""")
            .expecting(201)
            .capture(orderId) { it.header("location") },
    )
}

class CheckoutLoadTest {

    @LoadTest
    fun `checkout holds up at fifty a second`(kestrel: Kestrel) {
        val result = kestrel.run(checkout.at(50.perSecond, over = 1.minutes))

        assertTrue(result[placeOrder].responseTime.p99 < 200.milliseconds)
        assertEquals(0L, result.failed)
    }
}
```

That is the whole test. `@LoadTest` hands you a `Kestrel`, you assert on a value,
and it runs on virtual threads on the JDK's own HTTP client — nothing to install.
Everything is typed and named once: capture into `orderId` and it comes back a
`String`; rename `placeOrder` and the code stops compiling instead of silently
checking a step that no longer exists.

## Point it at anything

HTTP and WebSockets come in the box, and
[Pelican](https://github.com/matthewjones372/pelican) typed endpoints are steps too.

Anything else — gRPC, a database, a queue — is just a step body. Whatever you call
inside it is timed and recorded like any other step, so you use the client you
already have:

```kotlin
exec(settle) {
    val outcome = ledger.settle(order)   // your own client — gRPC, JDBC, a producer
    if (!outcome.ok) fail(outcome.reason)
}
```

And for work that finishes somewhere else — publish now, match the reply that arrives
on another channel later — the latency you measure is the real round trip, not the ack.

## What you get

- **A verdict you can believe.** Every run tells you whether the generator kept
  its own schedule. A green test on a generator that fell behind is the trap
  Kestrel exists to close.
- **Real percentiles.** Bars are counted buckets, a percentile is the top of the
  bucket a sample landed in, on a log axis. No smoothing, no interpolation — if a
  number is on the page, something measured it.
- **A report, not a web app.** One self-contained HTML file: data, styles and
  charts inline. It opens straight from disk, uploads as a CI artifact as-is, and
  does light and dark. [See a full one.](docs/assets/report-full-light.png)
- **Answers before you run.** A scenario is a value, so
  `checkout.at(50.perSecond, over = 1.minutes).profile.userCount()` is `3000`
  before a single request goes out.

<div align="center">
<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/report-distribution-dark.png">
  <img src="docs/assets/report-distribution-light.png" width="820" alt="A latency distribution: histogram bars with p50 and p99 markers.">
</picture>
</div>

## Coming from Gatling?

Gatling is mature and battle-tested, and Kestrel is not trying to replace it. But if you
write Kotlin, the difference is on line one:

| | Gatling, from Kotlin | Kestrel |
|---|---|---|
| A captured value | a string key, typed where it is read | `sessionKey<String>`, typed once |
| A step's name | a string, written again at each assertion | a handle — rename it and it will not compile |
| A path variable | `#{orderId}`, resolved mid-run | filled from the typed session |
| A simulation | a class you extend | a value you can print and inspect before it runs |
| The result | a report directory to parse | a value your test reads a number off |
| Did the generator keep up? | read the docs and hope | a verdict on every run |

## Get started

```kotlin
dependencies {
    testImplementation("io.github.matthewjones372:kestrel-http:0.1.0")
    testImplementation("io.github.matthewjones372:kestrel-junit5:0.1.0")
    testImplementation("io.github.matthewjones372:kestrel-report-html:0.1.0")
}
```

Write the test above, then write the report:

```kotlin
result.writeHtmlReport(Path.of("build/reports/kestrel/checkout.html"))
```

**[The cookbook](docs/cookbook.md) has the rest** — each recipe a few lines, with
a note on why it is those lines and not the obvious alternative:

| | |
|---|---|
| **Shaping load** | flat, ramped and staged profiles; Poisson arrivals; think time; a mix of journeys in one run |
| **Per-user data** | feeders as a function of the user number; CSV; a token refreshed off the measured path |
| **The requests** | captures, body checks, cookies across redirects, W3C trace ids, WebSocket streams, work that finishes on another topic |
| **Asking the question** | goals and goodput; steady state; capacity search; comparing runs against a baseline |
| **Keeping the answer** | the HTML report, a GitHub job summary, and a baseline in CI |

> [!NOTE]
> Early days. It runs and it's tested, but nothing is released to Maven Central
> yet — `specs/` tracks what is built and what is not.

## The rest

- **[docs/what-it-costs.md](docs/what-it-costs.md)** — the tool's own measured overhead, so you can trust the numbers above it.
- **[docs/modules.md](docs/modules.md)** — the modules and the coordinates to depend on them.
- **[AGENTS.md](AGENTS.md)** — read this first if you want to work on Kestrel itself.

```bash
./gradlew build          # tests, detekt, spotless, coverage
```

`kestrel-core` depends on the Kotlin standard library and nothing else, and a
test enforces it. Each module asserts its own runtime classpath, so core can't
grow a dependency and the Kotest module can't quietly start needing the JUnit one.

## License

Apache 2.0 — see [LICENSE](LICENSE).
