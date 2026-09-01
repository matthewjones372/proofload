<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/kestrel-mark-dark.svg">
  <img src="docs/assets/kestrel-mark-light.svg" width="88" height="88" alt="">
</picture>

# Kestrel

**Load testing for Kotlin, honest about its own numbers.**

Write a scenario as a plain Kotlin value. Run it. Get back a result your test
reads directly, and one self-contained HTML page that tells you whether to
trust it.

</div>

<div align="center">
<a href="docs/assets/report-full-light.png">
<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/report-verdict-dark.png">
  <img src="docs/assets/report-verdict-light.png" width="820" alt="A Kestrel run report: 2 of 4 goals met, with each goal's measurement beside it.">
</picture>
</a>

<sub>A real run. <code>POST /pay</code> missed its 300 ms target — and the green
<b>"the generator keeps its schedule"</b> is what proves that's the target's
fault, not the tool's. <a href="docs/assets/report-full-light.png">See the whole page →</a></sub>
</div>

> [!NOTE]
> Early, but it runs. `specs/` tracks what is built and what is not. Nothing is
> released yet.

## A complete test

```kotlin
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
            .capture(orderId) { it.header("location") },
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

That is the whole thing. No base class, no string keys to keep in sync, no URL
template that breaks halfway through a run, no report directory to parse
afterwards. `@LoadTest` hands you a `Kestrel`, and the result is a value you
assert on.

Everything is typed and named once. Capture into `orderId` and it reads back as
a `String`. Rename the `placeOrder` handle and the code stops compiling, instead
of leaving a test that checks a step nobody runs. And a scenario is just a value,
so you can ask it questions before it sends anything —
`checkout.at(50.perSecond, over = 1.minutes).profile.userCount()` is `3000`.

## Why it exists

**It stays out of your way.** Gatling has a Kotlin DSL, but it is a thin layer
over the Java one, and the things Kotlin is good at do not survive the trip:
keys are strings, steps are named twice, URLs are templated and checked at
runtime, and a simulation is a class you cannot hold or print. Kestrel drops all
of that. The compiler checks your scenario; your test framework reads the result.

**It tells you the truth.** When a load generator cannot keep up, it quietly
queues requests and then reports the wait as the target's latency. This is
*coordinated omission*, the classic load-testing bug, and most tools cannot even
detect it. Kestrel times each request from when it was *meant* to start, shows
the generator's own stalls next to the latency they would otherwise inflate, and
lets a verdict say *"cannot tell"* instead of guessing. If a number is in the
report, something measured it.

<div align="center">
<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/report-distribution-dark.png">
  <img src="docs/assets/report-distribution-light.png" width="820" alt="A latency distribution: histogram bars with p50 and p99 markers.">
</picture>

<sub>Every bar counted something. A percentile is the top of the bucket a sample
landed in, drawn on a log axis because latency is logarithmic. No smoothing, no
interpolation.</sub>
</div>

## Get started

```kotlin
dependencies {
    testImplementation("io.github.matthewjones372:kestrel-http:0.1.0")
    testImplementation("io.github.matthewjones372:kestrel-junit5:0.1.0")
    testImplementation("io.github.matthewjones372:kestrel-report-html:0.1.0")
}
```

Write the test above, then do what you like with the result — assert on it, or
write the report:

```kotlin
result.writeHtmlReport(Path.of("build/reports/kestrel/checkout.html"))
```

That is one file, with the data, styles and charts all inline. It opens straight
from disk and uploads as a CI artifact with nothing attached. The images above
are that page. The file itself is at
[docs/assets/example-report.html](docs/assets/example-report.html) to open after
a clone, and [the cookbook](docs/cookbook.md) shows how to publish a live one
from CI.

## Going further

**[docs/cookbook.md](docs/cookbook.md) is the recipe book.** Every capability
below is a few lines there, each with a note on why it is those lines and not the
obvious alternative:

| | |
|---|---|
| **Shaping load** | flat, ramped and staged profiles; Poisson arrivals; think time; a mix of journeys in one run |
| **Per-user data** | feeders as a function of the user number; CSV; a token refreshed off the measured path |
| **The requests** | captures, body checks, cookies across redirects, W3C trace ids, WebSocket streams, work that finishes on another topic |
| **Asking the question** | goals and goodput; steady state; capacity search; comparing runs; *did the generator keep up?* |
| **Keeping the answer** | the HTML report, a GitHub job summary, and a baseline in CI |

- **[docs/what-it-costs.md](docs/what-it-costs.md)** — the tool's own measured
  overhead, so you can trust the numbers above it.
- **[docs/modules.md](docs/modules.md)** — the modules and the coordinates to
  depend on them.

## Building

```bash
./gradlew build          # tests, detekt, spotless, coverage
./gradlew spotlessApply  # run before you commit
```

`smoke/` is a separate Gradle build that depends on the published coordinates, so
a wrong POM or a missing jar fails at resolution here rather than for a stranger:

```bash
./gradlew publishToMavenLocal && cd smoke && ../gradlew test
```

## Modules

| Module | Depends on | For |
|---|---|---|
| `kestrel-core` | **nothing** | scenarios, profiles and results as values, and the `Engine` that runs one |
| `kestrel-engine` | core | `VirtualThreads`: departures on a schedule, a user to a thread |
| `kestrel-http` | core | HTTP steps on `java.net.http` |
| `kestrel-websocket` | core | WebSocket steps on `java.net.http`, a connection per user |
| `kestrel-junit5` / `kestrel-kotest` | core, engine | a load test that is a `@Test`, or a Kotest spec |
| `kestrel-baseline` | core | a run kept in a file, to compare the next one to |
| `kestrel-report-html` / `kestrel-report-github` | core | a self-contained HTML page, or markdown for a job summary |
| `kestrel-pelican` | core, `pelican-core` | [Pelican](https://github.com/matthewjones372/pelican) endpoints as steps |

Every row is a test: each module asserts its own runtime classpath, so `core`
cannot grow a dependency and the Kotest module cannot quietly start needing the
JUnit one. `core` depends on the Kotlin standard library and nothing else.

Working in here? Read [AGENTS.md](AGENTS.md) first.

## License

Apache 2.0 — see [LICENSE](LICENSE).
