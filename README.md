<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/kestrel-mark-dark.svg">
  <img src="docs/assets/kestrel-mark-light.svg" width="88" height="88" alt="">
</picture>

# Kestrel

**Load testing for Kotlin, without the ceremony — and without the lies.**

A scenario is an ordinary Kotlin value you build, inspect and assert on.
A report is one honest page that tells you when the generator, not the target,
was the slow one.

</div>

<div align="center">
<a href="docs/assets/report-full-light.png">
<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/report-verdict-dark.png">
  <img src="docs/assets/report-verdict-light.png" width="820" alt="A Kestrel run report: 2 of 4 goals met, with each goal's measurement beside it.">
</picture>
</a>

<sub>A real run. `POST /pay` blew its 300 ms budget — and the green
<b>“the generator keeps its schedule”</b> is what proves that’s the target’s
fault and not the tool’s. <a href="docs/assets/report-full-light.png">See the whole page →</a></sub>
</div>

> [!NOTE]
> Early, but it runs. `specs/` records what is built and what is not; nothing is
> released yet.

## The test

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

No base class, no string session keys, no `#{}` template resolved at minute six,
and no report directory to go and parse: `@LoadTest` gives you a `Kestrel`, and
what you get back is a value your test reads a number off. `orderId` is a
`sessionKey<String>`, so a capture reads back typed; `placeOrder` is a handle,
so renaming it is a compile error rather than a test that quietly asserts about
a step nobody runs. And a scenario is just a value —
`checkout.at(50.perSecond, over = 1.minutes).profile.userCount()` is `3000`
before a single request goes out.

## Why it exists

**The ergonomics.** Gatling has a Kotlin DSL, but it is a Kotlin surface over a
Java one, and what Kotlin would buy does not survive the trip: session keys are
strings, steps are named twice, paths are templated and resolved at runtime, and
a simulation is a class you cannot hold, compose or print. Kestrel is that same
test with each of those removed — checked by the compiler, read by the test
framework.

**The honesty.** A generator that falls behind its own schedule reports its own
backlog as the target's latency — *coordinated omission*, the default bug in
this class of tool, and one most of them cannot even tell you about. Kestrel
measures the departure a run *promised* against the response, keeps the
injector's own stalls beside the tail they get blamed for, and lets a verdict
say **“cannot tell”** rather than invent one. A number in a Kestrel report is a
measurement or it is not there.

<div align="center">
<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/report-distribution-dark.png">
  <img src="docs/assets/report-distribution-light.png" width="820" alt="A latency distribution: histogram bars with p50 and p99 markers.">
</picture>

<sub>Every bar counted something. Percentiles are the top of the bucket a sample
landed in, on a log axis because latency is logarithmic — no smoothing, no
interpolation.</sub>
</div>

## Your first run

```kotlin
dependencies {
    testImplementation("io.github.matthewjones372:kestrel-http:0.1.0")
    testImplementation("io.github.matthewjones372:kestrel-junit5:0.1.0")
    testImplementation("io.github.matthewjones372:kestrel-report-html:0.1.0")
}
```

Write the test above, then read the run as a value — assert on it, write it to a
report, or compare it to last time:

```kotlin
result.writeHtmlReport(Path.of("build/reports/kestrel/checkout.html"))
```

One self-contained file: the data, the stylesheet and the charts inline, so it
opens from a `file://` URL and uploads as a CI artifact unchanged — light and
dark, sortable, honest about its own precision. The images above are that page,
straight from a run; the actual file lives at
[docs/assets/example-report.html](docs/assets/example-report.html) to open after
a clone, and [docs/cookbook.md](docs/cookbook.md) shows how to publish one to
GitHub Pages from CI.

## Going further

**[docs/cookbook.md](docs/cookbook.md) is the recipe book** — every capability
below is a few lines there, each with a note on why it is those lines and not
the obvious alternative:

| | |
|---|---|
| **Shaping load** | flat, ramped and staged profiles; Poisson arrivals; think time; a mix of journeys in one run |
| **Per-user data** | feeders as a function of the user number; CSV; a token refreshed off the measured path |
| **The requests** | captures, body checks, cookies across redirects, W3C trace ids, WebSocket streams, work that finishes on another topic |
| **Asking the question** | goals and goodput; steady state; capacity search; comparing runs; **“did the generator keep up?”** |
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

`smoke/` is a separate Gradle build that depends on the published coordinates,
so a wrong POM or a missing jar fails at resolution here rather than for a
stranger:

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
