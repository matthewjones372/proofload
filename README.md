<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/proofload-mark-dark.svg">
  <img src="docs/assets/proofload-mark-light.svg" width="88" height="88" alt="">
</picture>

# Proofload

Load testing for Kotlin.

[![build](https://github.com/matthewjones372/proofload/actions/workflows/build.yml/badge.svg)](https://github.com/matthewjones372/proofload/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.matthewjones372/proofload-core?label=maven%20central)](https://central.sonatype.com/artifact/io.github.matthewjones372/proofload-core)
<!-- Line coverage, which is what `koverVerify`'s floor is on. It is the kinder of
     two numbers (branch coverage over the same code is materially lower) and
     0116 carries that argument.
     The number is in the URL rather than fetched from a file, because a shields
     endpoint reads that file anonymously and raw.githubusercontent.com will not
     serve one from a private repository. `coverage-badge` rewrites this line. -->
[![coverage](https://img.shields.io/badge/coverage-92.7%25-brightgreen)](https://github.com/matthewjones372/proofload/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-2.4-7F52FF.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![JVM](https://img.shields.io/badge/JVM-21%2B-437291.svg?logo=openjdk&logoColor=white)](https://adoptium.net)

</div>

When a load generator can't keep up, it queues requests and reports the wait as
the server's latency. This is known as coordinated omission. The tail latency
looks fine and the test passes even though the service is slow.

Proofload times every request from the moment it was scheduled to start, and
checks on every run whether the generator kept to its schedule. If that check
passes, the numbers describe the target rather than the tool.

<div align="center">
<a href="docs/assets/report-full-light.png">
<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/report-verdict-dark.png">
  <img src="docs/assets/report-verdict-light.png" width="820" alt="A Proofload run report: 2 of 4 goals met, with each goal's measurement beside it.">
</picture>
</a>

<sub>A real run. <code>POST /pay</code> missed its 300 ms target. The passing
<b>"the generator keeps its schedule"</b> goal shows the miss came from the server
and not from the tool. <a href="docs/assets/report-full-light.png">The whole page</a>.</sub>
</div>

The example below uses HTTP and JUnit 5, which is the shortest way in. The same
scenarios can also send WebSocket, gRPC, Kafka and JDBC traffic, run inside
Kotest or ZIO Test, and be written from Java or Scala. A scenario can start from
a YAML plan, an OpenAPI document or a HAR recording, and a command-line tool runs
plans without a Kotlin build. A run can be kept as a baseline to compare the next
one against, and written out as an HTML page, GitHub markdown or metrics for
other tools. [Modules](#modules) lists every part and what it is for.

## Writing a test

A scenario is a plain Kotlin value. There is no base class to extend, no string
keys to keep in sync, and no separate DSL or Docker setup. A test runs a scenario
and asserts on the result:

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

`@LoadTest` passes in a `Proofload`, and the test asserts on the value `run`
returns. It runs on virtual threads using the JDK's HTTP client, so there is
nothing else to install. Keys and steps are typed and declared once: a capture
into `orderId` comes back as a `String`, and renaming `placeOrder` breaks
compilation rather than leaving an assertion on a step that no longer exists.

## Protocols

HTTP, server-sent events, WebSockets, gRPC, Kafka and JDBC come in the box, and
[Pelican](https://github.com/matthewjones372/pelican) typed endpoints are steps too.
Database steps time the connection checkout apart from the query, because a pool
your users queued for is not the database being slow.

For anything else, such as another queue or a cache, write a step body. Whatever
you call inside it is timed and recorded like any other step, so you can use the
client you already have:

```kotlin
exec(settle) {
    val outcome = ledger.settle(order)   // your own client, whatever it is
    if (!outcome.ok) fail(outcome.reason)
}
```

For work that finishes somewhere else (you publish now and match a reply that
arrives later on another channel), the measured latency is the full round trip
rather than the ack.

Each protocol is a module of its own, so a project only takes the clients it
uses:

- **HTTP and server-sent events**: `proofload-http`, on the JDK's
  `java.net.http` client. The client can be replaced through a transport
  interface.
- **WebSocket**: `proofload-websocket`. The handshake and the close are each
  timed, and `send` and `awaiting` time a message and the answers to it.
  [Cookbook](docs/cookbook.md#websockets).
- **gRPC**: `proofload-grpc` calls through your own generated stubs and leaves
  the transport (Netty or OkHttp) to you. `proofload-grpc-dynamic` makes the same
  calls with a JSON body, from a descriptor set or the server's reflection
  service, for when you have no stubs. A plan file can't name a gRPC call yet,
  so for now the dynamic module is only usable from Kotlin.
  [Cookbook](docs/cookbook.md#grpc).
- **Kafka**: `proofload-kafka` produces records and can read the answer off
  another topic by a correlation header. It brings no serializer and no schema
  registry: the value is a lambda that calls your own serializer.
  [Cookbook](docs/cookbook.md#kafka-and-the-answer-on-another-topic).
- **JDBC**: `proofload-jdbc` runs statements over your own `DataSource`. It
  holds no transaction across steps and sends no batches.
  [Cookbook](docs/cookbook.md#database-steps).
- **Pelican**: `proofload-pelican` runs a generated Pelican client on
  Proofload's transport, with no Pekko.

A database step, with the driver and pool your service already uses:

```kotlin
val orderId = sessionKey<Int>("orderId")
val byId = step("select an order")
val orders = jdbc.on(dataSource)

val reading = scenario("reading") {
    exec(byId, orders.query("select id, sku from orders where id = ?").binding { listOf(it[orderId]) })
}

// after the run
result[byId].serviceTime.p99   // what the database took
result[byId].waitedForPool.p99 // what users spent waiting for a connection
```

Only the HTTP path, and part of the Kafka path, have a measured overhead.
WebSocket, server-sent events, gRPC and JDBC have not been benchmarked yet.
[docs/what-it-costs.md](docs/what-it-costs.md#what-has-a-number-and-what-has-none)
says which numbers exist.

## Test frameworks

`proofload-junit5` provides the `@LoadTest` above. `proofload-kotest` does the
same in a Kotest spec, with no base class and nothing to register:

```kotlin
class CheckoutSpec : StringSpec({

    "checkout holds up at fifty a second" {
        val result = proofload().run(checkout.at(50.perSecond, over = 1.minutes))

        result[placeOrder].responseTime.p99 shouldBeLessThan 200.milliseconds
    }
})
```

`proofload-zio-test` runs a load test as a zio-test test. Extending
`ProofloadSpec` gives a spec that runs sequentially on the live clock, and the
run itself goes on ZIO's blocking executor. Kotest and zio-test are
`compileOnly` in these modules, so neither arrives on your classpath through
Proofload. [The cookbook](docs/cookbook.md#the-same-thing-in-a-zio-test-spec)
has a spec, and [a test framework is optional](docs/cookbook.md#without-a-test-framework).

## From Java and Scala

`proofload-java` builds the same scenario values from Java, with static methods,
builders and `java.time.Duration`. It covers scenarios, HTTP steps, running,
goals, reading a result and the capacity search. Baselines, sharding and the
exports are not in it yet.

```java
Scenario checkout = Scenarios.named("checkout")
    .exec(BROWSE, api.get("/products"))
    .exec(PLACE_ORDER, Https.capturing(
        api.post("/orders").body("{\"cart\":\"1 anvil\"}").expecting(201),
        ORDER_ID,
        response -> response.header("location")))
    .pause(Duration.ofSeconds(1))
    .build();
```

`proofload-scala` is a Scala 3 layer over the Java facade, with
`FiniteDuration` in both directions, `50.perSecond` and `sessionKey[T]`. It is
compiled against Scala 3.3 LTS so that any later Scala 3 compiler can read it.
Don't use `0.1.0-rc3` from Scala: it was built with Scala 3.9 by mistake.

[examples-java](examples-java/src/main/java/io/github/matthewjones372/proofload/examples/java/Checkout.java)
and [examples-scala](examples-scala/src/main/scala/io/github/matthewjones372/proofload/examples/scala/Checkout.scala)
each hold a complete load test that the build compiles, and examples-scala has a
[zio-test spec](examples-scala/src/test/scala/io/github/matthewjones372/proofload/examples/scala/CheckoutSpec.scala)
that the build runs. [docs/from-java.md](docs/from-java.md) and
[docs/from-scala.md](docs/from-scala.md) walk through them.

## Plans, recordings and contracts

A scenario doesn't have to start as Kotlin.

**A plan file.** `proofload-plan` reads `plan/1`, a YAML or JSON description of
a scenario, its load and its goals, and turns it into the same values the Kotlin
DSL builds. A plan can send HTTP requests and produce to Kafka topics
(`proofload-plan-kafka` supplies the Kafka half, so `proofload-plan` carries no
Kafka client), and can draw per-user values with `draw`. When a plan outgrows
the format, `emit` prints it as Kotlin.

```yaml
proofload:  plan/1
baseUrl:  https://orders.internal
scenario: checkout
steps:
  - name: browse
    get:  /products
  - name: place order
    post: /orders
    body: '{"cart":"1 anvil"}'
    expecting: 201
load:
  rate: 50/s
  over: 1m
goals:
  - step: place order
    p99:  200ms
```

**The command line.** `proofload-cli` has five commands: `validate`, `preview`,
`run`, `emit` and `from-openapi`. Only `run` sends load, and it is held to a
`proofload.toml` in the working directory if there is one
([docs/allowance.md](docs/allowance.md)). The exit code is the verdict: 0 met,
1 missed a goal, 2 the generator fell behind, 3 refused, 4 unusable. The
published jar names its main class, so [JBang](https://www.jbang.dev) can start
it from the coordinate:

```bash
jbang io.github.matthewjones372:proofload-cli:0.1.0-rc4 preview checkout.yaml
jbang io.github.matthewjones372:proofload-cli:0.1.0-rc4 run checkout.yaml
```

**OpenAPI.** `proofload-openapi` reads an OpenAPI document and writes a plan,
drawing parameter values from the ranges and enums the document declares. The
CLI's `from-openapi` and the MCP server's `from_openapi` both use it;
[the MCP section](#using-it-from-an-agent-mcp) below has an example.

**Pelican endpoints.** `proofload-contract` writes a plan from Pelican endpoint
values. It is build-time tooling, and what it writes is a first draft: one step
per GET endpoint in the order they were declared, a smoke-sized load and a
placeholder p99 goal per step, all for you to edit. Reading an OpenAPI document
through Pelican's importer is planned and not built.

**A HAR recording.** `proofload-record` reads a HAR file, as exported by a
browser or a proxy, into Kotlin source that you edit and commit. A value one
response produced and a later request used becomes a capture, repeated paths
collapse into one step, static assets are left out, and every credential is
replaced by a `TODO`. It doesn't record traffic itself.
[Cookbook](docs/cookbook.md#start-from-traffic-you-already-have).

**Generated data.** `proofload-arbs` has generators that are a function of the
user's number: uniform, Zipf, a list of values, weighted choices, digits and
UUIDs. Cardinality and skew are then something you choose, and a run can be
repeated exactly. A plan's `draw` uses the same generators.
[Cookbook](docs/cookbook.md#data-you-do-not-have).

## Features

- **Schedule check.** Every run reports whether the generator kept its own
  schedule, so a test can't pass on a generator that fell behind.
- **Percentiles from counts.** Histogram bars are counted buckets on a log axis,
  and a percentile is the top of the bucket a sample landed in. There is no
  smoothing or interpolation.
- **HTML report.** One self-contained file with the data, styles and charts
  inline. It opens from disk, can be uploaded as a CI artifact as is, and has
  light and dark themes. [A full example.](docs/assets/report-full-light.png)
- **Inspecting a scenario before running it.** A scenario is a value, so
  `checkout.at(50.perSecond, over = 1.minutes).profile.userCount()` is `3000`
  before any request is sent.
- **Measured overhead.** The HTTP step sustains at least 2,500 requests a
  second, and a step that touches no socket sustains 100,000, on four cores
  shared with the target, over loopback, untuned.
  [docs/what-it-costs.md](docs/what-it-costs.md) has the tables, the machine and
  what each sweep left out.

The HTTP figure is a conservative lower bound. 5,000/s also kept the median
departure within a millisecond, but three requests in twenty-five thousand were
refused, so it was not used. Neither figure is a comparison with another tool,
and this repository doesn't publish any.

`fellBehind()` is not a capacity verdict. It asks whether the generator's own p99
lateness is large enough to show up next to the target's p99, which at
microsecond latencies it usually is. A `yes` at a hundred requests a second means
the target was fast, not that the tool struggled.

<div align="center">
<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/report-distribution-dark.png">
  <img src="docs/assets/report-distribution-light.png" width="820" alt="A latency distribution: histogram bars with p50 and p99 markers.">
</picture>
</div>

## Baselines, reports and exports

`proofload-report-html` writes the page shown above. The other modules here
keep a run or pass its numbers on:

- **GitHub**: `proofload-report-github` renders a run as a markdown table,
  appends it to the Actions job summary, and writes an index page over a
  directory of reports for GitHub Pages.
- **Baselines**: `proofload-baseline` writes a run to a file and reads it back,
  so the next run can be compared with it. Each step comes back better, worse,
  indistinguishable, added or gone. Two runs of different plans are refused, and
  runs from different machines come with a warning. It can also read a
  directory of runs as one population, and a series of baselines as a trend.
- **Exports**: `proofload-export` writes a run as a JSON document, an
  HdrHistogram log or an OpenMetrics exposition.
  [docs/exporting.md](docs/exporting.md).
- **OpenTelemetry**: `proofload-otel` sends a run's measurements to a collector
  over OTLP/HTTP, and can push counts while a run is going. Percentiles are only
  sent once the run is over.
- **Several injectors**: a run can be split across machines with
  `sharded(index, of, startingAt)` and the pieces merged back into one result.
  There is no coordinator; you start each injector yourself.
  [docs/more-than-one-injector.md](docs/more-than-one-injector.md).

A baseline in CI, from the
[example the build compiles](examples/src/main/kotlin/io/github/matthewjones372/proofload/examples/AgainstTheBaseline.kt):

```kotlin
val previous = baseline.takeIf { Files.exists(it) }?.let(::readBaseline)
val comparison = result.against(previous)

result.appendToStepSummary(comparison, floor)
result.writeBaseline(baseline)
```

A comparison reads p99 of response time. On `main`, and not yet released,
`result.against(previous, of = Clock.ServiceTime)` compares service time
instead, which is the better reading of a run where the generator fell behind.

## Get started

```kotlin
dependencies {
    testImplementation("io.github.matthewjones372:proofload-http:0.1.0-rc4")
    testImplementation("io.github.matthewjones372:proofload-junit5:0.1.0-rc4")
    testImplementation("io.github.matthewjones372:proofload-report-html:0.1.0-rc4")
}
```

The other modules are added the same way; [Modules](#modules) lists them.
Write the test above, then write the report:

```kotlin
result.writeHtmlReport(Path.of("build/reports/proofload/checkout.html"))
```

[The cookbook](docs/cookbook.md) covers the rest. Each recipe is a few lines,
with a note on why it is written that way rather than the obvious alternative:

| | |
|---|---|
| **[The vocabulary](docs/concepts.md)** | what a p99 is and why not an average; why lateness is a verdict; what Little's law catches |
| **Shaping load** | flat, ramped and staged profiles; Poisson arrivals; think time; a mix of journeys in one run |
| **Per-user data** | feeders as a function of the user number; CSV; a token refreshed off the measured path |
| **The requests** | captures, body checks, bodies filled per user or streamed from disk, cookies across redirects, W3C trace ids, WebSocket and SSE streams, gRPC, Kafka, work that finishes on another topic |
| **Asking the question** | goals and goodput; steady state; capacity search; comparing runs against a baseline |
| **Keeping the answer** | the HTML report, a GitHub job summary, and a baseline in CI |

> [!NOTE]
> This is early. `0.1.0-rc4` is the current release, and every coordinate on
> this page and under `docs/` is pinned to it. It is a release candidate, so the
> API may still change before `0.1.0`. `specs/` tracks what is built and what
> is not.
>
> `main` has changes made since `v0.1.0-rc4` that are not on Maven Central.
> [CHANGELOG.md](CHANGELOG.md) lists everything under `0.1.0` without
> separating the two, so `git log v0.1.0-rc4..main` is the way to tell. On
> `main`, release candidates after rc4 are set to publish to Central's snapshot
> repository, which drops them after 90 days.

## Modules

Every library module below is on Maven Central at `0.1.0-rc4`, under the group
`io.github.matthewjones372`, and they are versioned together.
`proofload-core` depends on the Kotlin standard library and nothing else. The
other modules sit beside it, and each one has a test that checks what is on its
classpath. [docs/modules.md](docs/modules.md) has the dependencies of each.

**Core**

| Module | What it is for | On Central |
|---|---|---|
| `proofload-core` | Scenarios, load profiles, goals and results, all as values | yes |
| `proofload-engine` | Runs a simulation on virtual threads, departing on a schedule; `Proofload` is here | yes |

**Protocols**

| Module | What it is for | On Central |
|---|---|---|
| `proofload-http` | HTTP steps on the JDK client, and server-sent event streams | yes |
| `proofload-websocket` | WebSocket steps on the JDK's WebSocket client | yes |
| `proofload-grpc` | gRPC steps through your own generated stubs | yes |
| `proofload-grpc-dynamic` | gRPC calls with no stubs, from a descriptor set or server reflection; not usable from a plan yet | yes |
| `proofload-kafka` | Kafka produce steps, and answers read off another topic | yes |
| `proofload-jdbc` | Statements over your own `DataSource`, with the pool wait counted apart | yes |
| `proofload-pelican` | [Pelican](https://github.com/matthewjones372/pelican) endpoints as steps | yes |

**Test frameworks**

| Module | What it is for | On Central |
|---|---|---|
| `proofload-junit5` | `@LoadTest`: a load test that is a JUnit 5 test | yes |
| `proofload-kotest` | The same in a Kotest spec | yes |
| `proofload-zio-test` | The same in a zio-test spec | yes |

**Languages**

| Module | What it is for | On Central |
|---|---|---|
| `proofload-java` | A Java facade: static methods, builders and `java.time.Duration`; covers scenarios, HTTP, running and goals | yes |
| `proofload-scala` | A Scala 3 layer over the Java facade, with `FiniteDuration` | yes |

**Building scenarios**

| Module | What it is for | On Central |
|---|---|---|
| `proofload-plan` | Reads a `plan/1` file into a scenario, and prints one back as Kotlin | yes |
| `proofload-plan-kafka` | Lets a plan produce to Kafka topics | yes |
| `proofload-openapi` | Writes a plan from an OpenAPI document | yes |
| `proofload-contract` | Writes a draft plan from Pelican endpoint values; OpenAPI through Pelican is not built yet | yes |
| `proofload-record` | Reads a HAR recording into Kotlin source to edit and commit | yes |
| `proofload-arbs` | Per-user generators with a stated cardinality and skew | yes |

**Running and reporting**

| Module | What it is for | On Central |
|---|---|---|
| `proofload-cli` | `validate`, `preview`, `run`, `emit` and `from-openapi` from a shell, with the verdict as the exit code | yes |
| `proofload-report-html` | One self-contained HTML report | yes |
| `proofload-report-github` | Markdown, the Actions job summary and a Pages index | yes |
| `proofload-baseline` | A run kept in a file and compared with the next one | yes |
| `proofload-export` | JSON, HdrHistogram log and OpenMetrics output | yes |
| `proofload-otel` | Measurements sent to an OpenTelemetry collector | yes |

**Agents**

| Module | What it is for | On Central |
|---|---|---|
| `proofload-mcp` | The CLI's calls as an MCP server, [described below](#using-it-from-an-agent-mcp) | yes |

**Examples and checks**

These are part of the repository and are not published.

| Directory | What it is for | On Central |
|---|---|---|
| `examples` | Tests where the modules are used together, including the plan and Kotlin examples the docs quote | no |
| `examples-java` | A load test in Java, so a Java compiler checks the facade | no |
| `examples-scala` | A load test and a zio-test spec in Scala, compiled and run by the build | no |
| `smoke` | A separate Gradle build that depends on the published coordinates, to catch a broken POM or a missing jar | no |
| `benchmarks` | Measures the tool's own overhead for [docs/what-it-costs.md](docs/what-it-costs.md) | no |

## Using it from an agent (MCP)

`proofload-mcp` is a stdio MCP server. Each of its tools is a call the CLI already
makes, so a model gets the same behaviour as a person at a terminal and there is
no second implementation to keep in step.

It is on Maven Central, so adding it needs no clone or build:

```bash
claude mcp add proofload -- jbang io.github.matthewjones372:proofload-mcp:0.1.0-rc4
```

The coordinate is enough because the published jar has a `Main-Class`.
[Coursier](https://get-coursier.io) works too, with `cs launch`, from the release
after `0.1.0-rc4`; `docs/mcp.md` has the flag that one and earlier need.
`java -jar` does not work: the jar is thin and has no classpath, so whatever
starts it has to resolve the POM.

Two other routes need no launcher. One is a download from the GitHub release,
which includes a `.bat` and so also works on Windows. The other is a container:

```bash
claude mcp add proofload -- docker run -i --rm -v "$PWD:/work:ro" ghcr.io/matthewjones372/proofload-mcp:0.1.0-rc4
```

With that command, `benchmark`, `plan_schema`, `validate`, `preview`, `smoke` and
`trace` work with no other setup. Only `run` needs a fence: write a
`proofload.toml` in the directory you started it from and it is picked up, since
that directory is what the mount passes in.

Mount the directory rather than the file. `-v "$PWD/proofload.toml:..."` looks
tidier, but if the file doesn't exist yet Docker creates a directory called
`proofload.toml` in your working directory, which then can't be read as an
allowance. Mounting the directory avoids this.

[docs/mcp.md](docs/mcp.md#starting-it) has all four routes.

Maven Central and the release download need no account. The GHCR image does: its
package is still private, so `docker` needs `docker login ghcr.io` until that
changes.

To work on Proofload itself, or to try an unreleased change, build it instead:

```bash
./gradlew :proofload-mcp:installDist
claude mcp add proofload -- "$PWD/proofload-mcp/build/install/proofload-mcp/bin/proofload-mcp"
```

A base URL is enough to start. `benchmark` writes a plan, validates it, previews
what it would send, sends one request per step, and then lists what it had to
guess:

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
    actually matter: a journey, a hot endpoint, a slow one?
  - The rate is a placeholder, one a second, because nothing said otherwise.
    What does this see at peak, and over how long?
```

The questions come from that plan and that smoke test. For example, it asks about
a credential only if the target answered 401. Once you answer, it sets the rate
and calls `run`. `run` returns straight away instead of blocking for the length
of the test, so you poll it:

```json
{"runId":"r-3f9c1a04","sending":"3000 requests over 1m to orders.internal"}
{"state":"sending","remaining":"41s"}
```

When it lands, `status` is the same document the HTML report is drawn from:
every goal `met` or not, with the `remedy` beside the one that missed.

`status` is meant for a model. `summary` is meant for you, in the chat: the
markdown a GitHub job summary carries (the step table, the behind verdict,
failures and totals), followed by the distribution the percentiles were read
from, which the numbers alone don't show:

```
checkout  p50 10.0ms  p99 2.00s
 10ms  ############  900
100ms
   1s  #  100
```

Nine in ten requests answered in 10 ms and the rest took two seconds. A p99 of
2 s on its own looks like a uniformly slow step, but here it is a cache missing a
tenth of the time. The bars are the counted buckets on the same log axis as the
HTML report, with the count printed beside each one, and a decade with no
samples is left blank.

**OpenAPI.** If the service has an OpenAPI document, `from_openapi` reads it
(YAML or JSON) and writes the plan it describes, so the paths, methods and step
names come from the contract. `baseUrl` is only needed when the document names
no server.

It also reads the range of each parameter. Repeating one id only measures one
row and one cache line, and cardinality and skew affect the p99. `minimum: 1, maximum: 500` becomes a uniform draw over exactly that
range, and `enum: [emea, apac, amer]` becomes every value it lists rather than
the first one, so the plan it writes arrives with its draws already in it:

```yaml
draw:
  sku:    {uniform: {from: 1, to: 500}}
  region: {oneOf: [emea, apac, amer]}
steps:
  - name: getProduct
    get: '/products/{sku}'
  - name: getRegion
    get: '/regions/{region}'
```

The braces survive so the draw has something to fill: `{sku}` in a path or a body
is filled per user from the session key of that name. A parameter the contract
does not bound is substituted rather than drawn, so the plan doesn't invent a
cardinality the contract never stated.

`draw` can be written in any plan, not only generated ones. The generators are
`uniform`, `zipf`, `oneOf`, `digits` and `uuids`. `zipf` matches the shape of
much real traffic, with a few keys requested constantly and a long tail
requested once:

```yaml
draw:
  sku: {zipf: {keys: 1000000, skew: 1.1}}
```

**Only `run` sends load.** The other tools send nothing, or one request per
step, and [the table](docs/mcp.md#the-tools) says which. A model still working
out a plan can't accidentally send three thousand requests a second.
`proofload.toml` limits what `run` may do by host, rate, duration and request
count.

**[docs/mcp.md](docs/mcp.md)** is the reference: every tool, what it sends, what
it refuses, and the `plan/1` format a model can ask for instead of guessing.

## Further reading

- **[docs/what-it-costs.md](docs/what-it-costs.md)**: the tool's own measured overhead.
- **[docs/invariants.md](docs/invariants.md)**: the sixteen statements a correct measurement depends on, and the five that do not hold yet.
- **[docs/modules.md](docs/modules.md)**: the modules and the coordinates to depend on them.
- **[docs/mcp.md](docs/mcp.md)**: the MCP server in full: every tool, what each one sends, and what it refuses.
- **[docs/allowance.md](docs/allowance.md)**: `proofload.toml`, the fence a run is held to, and why it is a fence rather than a sandbox.
- **[docs/exporting.md](docs/exporting.md)**: a run's measurements in the formats other tools read.
- **[docs/more-than-one-injector.md](docs/more-than-one-injector.md)**: one run sent from several machines, and read back as the run they were pieces of.
- **[docs/for-agents.md](docs/for-agents.md)**: every public signature, rendered from the `.api` dumps, for handing to a model.
- **[docs/from-java.md](docs/from-java.md)**: writing a load test in Java, and why the facade is a module rather than annotations on core.
- **[docs/from-scala.md](docs/from-scala.md)**: the same from Scala 3, over the Java facade, with `FiniteDuration` both ways, and a load test that is a zio-test test.
- **[docs/cookbook.md](docs/cookbook.md)**: recipes from a first test to Kafka, gRPC and a baseline in CI.
- **[CHANGELOG.md](CHANGELOG.md)**: what changed, and the current limitations.
- **[specs/ROADMAP.md](specs/ROADMAP.md)**: which specs are built and which are only drafted.
- **[llms.txt](llms.txt)**: a one-page summary for a model to read first.
- **[AGENTS.md](AGENTS.md)**: read this first if you want to work on Proofload itself.

```bash
./gradlew build          # tests, detekt, spotless, coverage
```

`proofload-core` depends on the Kotlin standard library and nothing else, and a
test enforces it. Each module asserts its own runtime classpath, so core can't
gain a dependency and the Kotest module can't start depending on the JUnit one.

## License

Apache 2.0. See [LICENSE](LICENSE).
