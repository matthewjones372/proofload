<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/proofload-mark-dark.svg">
  <img src="docs/assets/proofload-mark-light.svg" width="88" height="88" alt="">
</picture>

# Proofload

### Load testing for Kotlin — with a p99 you can trust.

[![build](https://github.com/matthewjones372/proofload/actions/workflows/build.yml/badge.svg)](https://github.com/matthewjones372/proofload/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.matthewjones372/proofload-core?label=maven%20central)](https://central.sonatype.com/artifact/io.github.matthewjones372/proofload-core)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-2.4-7F52FF.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![JVM](https://img.shields.io/badge/JVM-21%2B-437291.svg?logo=openjdk&logoColor=white)](https://adoptium.net)

</div>

**A passing load test can still ship a slow service.** When the load generator
can't keep up, it quietly queues requests and reports the wait as your server's
latency — the classic *coordinated omission* bug — so the tail looks fine and the
test goes green anyway.

Proofload takes a different approach: it times every request from the moment it was
*meant* to start, and proves on every run whether the generator kept up. Green
means the numbers are the target's, not the tool's.

<div align="center">
<a href="docs/assets/report-full-light.png">
<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/report-verdict-dark.png">
  <img src="docs/assets/report-verdict-light.png" width="820" alt="A Proofload run report: 2 of 4 goals met, with each goal's measurement beside it.">
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
    fun `checkout holds up at fifty a second`(proofload: Proofload) {
        val result = proofload.run(checkout.at(50.perSecond, over = 1.minutes))

        assertTrue(result[placeOrder].responseTime.p99 < 200.milliseconds)
        assertEquals(0L, result.failed)
    }
}
```

That is the whole test. `@LoadTest` hands you a `Proofload`, you assert on a value,
and it runs on virtual threads on the JDK's own HTTP client — nothing to install.
Everything is typed and named once: capture into `orderId` and it comes back a
`String`; rename `placeOrder` and the code stops compiling instead of silently
checking a step that no longer exists.

## Point it at anything

HTTP, server-sent events, WebSockets, gRPC, Kafka and JDBC come in the box, and
[Pelican](https://github.com/matthewjones372/pelican) typed endpoints are steps too.
Database steps time the connection checkout apart from the query, because a pool
your users queued for is not the database being slow.

Anything else — another queue, a cache — is just a step body. Whatever you call
inside it is timed and recorded like any other step, so you use the client you
already have:

```kotlin
exec(settle) {
    val outcome = ledger.settle(order)   // your own client, whatever it is
    if (!outcome.ok) fail(outcome.reason)
}
```

And for work that finishes somewhere else — publish now, match the reply that arrives
on another channel later — the latency you measure is the real round trip, not the ack.

## Or hand the whole thing to an agent

`proofload-mcp` is a stdio MCP server whose every tool is a call the CLI already
makes, so a model gets what a person at a terminal gets and there is no second
behaviour to keep in step.

```bash
./gradlew :proofload-mcp:installDist
claude mcp add proofload -- "$PWD/proofload-mcp/build/install/proofload-mcp/bin/proofload-mcp"
```

A base URL is enough to start. `benchmark` writes a plan, validates it, previews
what it would send, and sends one request per step — then says what it had to
guess rather than pretending it knew:

```
> Does checkout hold up?  https://orders.internal

proofload:  plan/1
baseUrl:  https://orders.internal
scenario: smoke
steps:
  - name: root
    get: /
load:
  rate: 1/s
  over: 10s

Running this would send 10 requests over 10s peaking at 1.0/s to orders.internal.
One request per step, already sent: 1 requests, 1 ok, 0 failed

Nothing above sent load. Call `run` with this plan to do that.

This plan is guessing. Ask whoever wants the benchmark:
  - This only sends `GET /`, because a base URL is all it was given. Which paths
    actually matter — a journey, a hot endpoint, a slow one?
  - The rate is a placeholder — one a second, because nothing said otherwise.
    What does this see at peak, and over how long?
```

The questions come from that plan and that smoke: a credential is asked about
because the target answered 401, not because targets often need one. You answer,
it raises the rate on purpose and calls `run` — which returns rather than
blocking for ten minutes, so you poll it:

```json
{"runId":"r-3f9c1a04","sending":"3000 requests over 1m to orders.internal"}
{"state":"sending","remaining":"41s"}
```

When it lands, `status` is the same document the HTML report is drawn from —
every goal `met` or not, with the `remedy` beside the one that missed.

**Only `run` sends load.** Everything else sends nothing, or one request per
step, and [the table](docs/mcp.md#the-tools) says which before you call it — so
a model still working out your plan cannot find that out at three thousand a
second. `proofload.toml` fences what it may do at all, by host, rate, duration
and request count.

**[docs/mcp.md](docs/mcp.md)** is the reference: every tool, what it sends, what
it refuses, and the `plan/1` format a model can ask for instead of guessing.

## What you get

- **A verdict you can believe.** Every run tells you whether the generator kept
  its own schedule. A green test on a generator that fell behind is the trap
  Proofload exists to close.
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

## Get started

```kotlin
dependencies {
    testImplementation("io.github.matthewjones372:proofload-http:0.1.0")
    testImplementation("io.github.matthewjones372:proofload-junit5:0.1.0")
    testImplementation("io.github.matthewjones372:proofload-report-html:0.1.0")
}
```

Write the test above, then write the report:

```kotlin
result.writeHtmlReport(Path.of("build/reports/proofload/checkout.html"))
```

**[The cookbook](docs/cookbook.md) has the rest** — each recipe a few lines, with
a note on why it is those lines and not the obvious alternative:

| | |
|---|---|
| **[The vocabulary](docs/concepts.md)** | what a p99 is and why not an average; why lateness is a verdict; what Little's law catches |
| **Shaping load** | flat, ramped and staged profiles; Poisson arrivals; think time; a mix of journeys in one run |
| **Per-user data** | feeders as a function of the user number; CSV; a token refreshed off the measured path |
| **The requests** | captures, body checks, bodies filled per user or streamed from disk, cookies across redirects, W3C trace ids, WebSocket and SSE streams, gRPC, Kafka, work that finishes on another topic |
| **Asking the question** | goals and goodput; steady state; capacity search; comparing runs against a baseline |
| **Keeping the answer** | the HTML report, a GitHub job summary, and a baseline in CI |

> [!NOTE]
> Early days. It runs and it's tested, but nothing is released to Maven Central
> yet — `specs/` tracks what is built and what is not.

## The rest

- **[docs/what-it-costs.md](docs/what-it-costs.md)** — the tool's own measured overhead, so you can trust the numbers above it.
- **[docs/modules.md](docs/modules.md)** — the modules and the coordinates to depend on them.
- **[docs/mcp.md](docs/mcp.md)** — the MCP server in full: every tool, what each one sends, and what it refuses.
- **[docs/allowance.md](docs/allowance.md)** — `proofload.toml`, the fence a run is held to, and why it is a fence rather than a sandbox.
- **[docs/exporting.md](docs/exporting.md)** — a run's measurements in the formats other tools read.
- **[docs/more-than-one-injector.md](docs/more-than-one-injector.md)** — one run sent from several machines, and read back as the run they were pieces of.
- **[docs/for-agents.md](docs/for-agents.md)** — every public signature, rendered from the `.api` dumps, for handing to a model.
- **[docs/from-java.md](docs/from-java.md)** — writing a load test in Java, and why the facade is a module rather than annotations on core.
- **[docs/from-scala.md](docs/from-scala.md)** — the same from Scala 3, over the Java facade, with `FiniteDuration` both ways, and a load test that is a zio-test test.
- **[AGENTS.md](AGENTS.md)** — read this first if you want to work on Proofload itself.

```bash
./gradlew build          # tests, detekt, spotless, coverage
```

`proofload-core` depends on the Kotlin standard library and nothing else, and a
test enforces it. Each module asserts its own runtime classpath, so core can't
grow a dependency and the Kotest module can't quietly start needing the JUnit one.

## License

Apache 2.0 — see [LICENSE](LICENSE).
