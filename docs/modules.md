# Modules

Proofload is split so that a project takes the integrations it uses and no
others. A test that only sends HTTP does not get a Kafka client, a gRPC runtime
or an OpenTelemetry SDK on its classpath.

## The dependency model

- **`proofload-core`** holds the model: scenarios, steps, session keys, feeders,
  load profiles, goals and results. It depends on the Kotlin standard library
  and nothing else. It also declares the `Engine` interface
  (`fun run(simulation: Simulation): RunResult`) but does not implement it.
- **`proofload-engine`** implements it. It runs a simulation on virtual
  threads, departing each user on its schedule, and holds `Proofload`, the
  runner the test-framework modules hand you.
- **Protocol modules** (`proofload-http`, `proofload-websocket`,
  `proofload-grpc`, `proofload-kafka`, `proofload-jdbc`, `proofload-pelican`)
  each add one kind of step. Each brings its own client library, or none where
  the JDK provides the client.
- **Everything else is an adapter**: a test framework, a language facade, a
  plan format or importer, a report format or a metrics sink. Each sits beside
  core and carries its own third-party dependency, if it has one.

A dependency added to core fails the build. Each module's runtime classpath is
asserted by a test in that module, listed in [the last section](#how-the-dependency-claims-are-checked).

## Choosing modules

Most projects need three: a protocol, a test framework and a report.

```kotlin
// build.gradle.kts
dependencies {
    testImplementation("io.github.matthewjones372:proofload-http:0.1.0-rc5")
    testImplementation("io.github.matthewjones372:proofload-junit5:0.1.0-rc5")
    testImplementation("io.github.matthewjones372:proofload-report-html:0.1.0-rc5")
}
```

`proofload-core` arrives through any of them, and `proofload-engine` through
the test-framework modules. Every library module is published to Maven Central
under the group `io.github.matthewjones372`, and all modules share one version.
[versions.md](versions.md) says which version to use and what `main` has that
the last release does not.

| If you want to | Add |
|---|---|
| send HTTP requests or read server-sent events | `proofload-http` |
| hold WebSocket connections | `proofload-websocket` |
| call gRPC methods through your generated stubs | `proofload-grpc`, plus a gRPC transport of your choice |
| call gRPC methods without stubs | `proofload-grpc-dynamic` |
| produce to Kafka and time the answer on another topic | `proofload-kafka` |
| run statements against your own `DataSource` | `proofload-jdbc` |
| call Pelican endpoints | `proofload-pelican` |
| write the test in JUnit, Kotest or zio-test | `proofload-junit5`, `proofload-kotest` or `proofload-zio-test` |
| run without a test framework | `proofload-engine` |
| write it in Java or Scala | `proofload-java` or `proofload-scala` |
| generate per-user data with a stated cardinality and skew | `proofload-arbs` |
| describe a load test in YAML or JSON | `proofload-plan`, and `proofload-plan-kafka` for Kafka steps |
| start from an OpenAPI document, a HAR file or Pelican endpoints | `proofload-openapi`, `proofload-record` or `proofload-contract` |
| run plans from a shell or an MCP client | `proofload-cli` or `proofload-mcp` |
| write an HTML report | `proofload-report-html` |
| write a GitHub job summary or a Pages index | `proofload-report-github` |
| keep a run and compare the next one with it | `proofload-baseline` |
| export JSON, an HdrHistogram log or OpenMetrics | `proofload-export` |
| send measurements to an OpenTelemetry collector | `proofload-otel` |

## Every module

"Proofload modules" are the `api` and `implementation` dependencies on other
modules in this repository. "Third-party" is what reaches your runtime
classpath from outside it.

### Model and engine

| Module | What it is | Proofload modules | Third-party |
|---|---|---|---|
| `proofload-core` | scenarios, profiles, feeders, goals and results as values; the `Engine` interface | none | none |
| `proofload-engine` | runs a simulation on virtual threads; `Proofload`, `run`, `trace`, `calibrate` | core | none |

### Protocols

| Module | What it is | Proofload modules | Third-party |
|---|---|---|---|
| `proofload-http` | HTTP steps and server-sent event streams on the JDK's `java.net.http` client, with a replaceable `Transport` | core | none |
| `proofload-websocket` | WebSocket steps on the JDK's `java.net.http.WebSocket` | core | none |
| `proofload-grpc` | gRPC calls through your own generated stubs, named by method descriptor | core | `grpc-api`, `grpc-stub` (no transport) |
| `proofload-grpc-dynamic` | gRPC calls from a descriptor set or server reflection, with JSON bodies | core, grpc | `protobuf-java`, `protobuf-java-util`, `grpc-protobuf-lite`, `grpc-services` |
| `proofload-kafka` | produce steps, and completions read off another topic | core | `kafka-clients` |
| `proofload-jdbc` | statements over your own `DataSource`, with the pool wait recorded apart | core | none (the driver and pool are yours) |
| `proofload-pelican` | [Pelican](https://github.com/matthewjones372/pelican) endpoints as steps | core | `pelican-core` (and its `jackson-core`) |

### Test frameworks and languages

| Module | What it is | Proofload modules | Third-party |
|---|---|---|---|
| `proofload-junit5` | `@LoadTest`, a JUnit Jupiter test with a `Proofload` parameter | core, engine | `junit-jupiter-api` (from the JUnit 6 line, which keeps the Jupiter API) |
| `proofload-kotest` | `proofload()` inside a Kotest spec | core, engine | none; Kotest is `compileOnly` |
| `proofload-zio-test` | `ProofloadSpec` and `measured` for zio-test | scala, and through it java, engine, http, core | none; ZIO and zio-test are `compileOnly` |
| `proofload-java` | static factories, builders and `java.time.Duration` over core's values | core, engine, http | none |
| `proofload-scala` | a Scala 3 layer over `proofload-java`, with `FiniteDuration` | java | `scala3-library_3` |

`proofload-scala` and `proofload-zio-test` also declare the two report modules
as `compileOnly`: their report calls work when you add those modules yourself.

### Plans, importers and data

| Module | What it is | Proofload modules | Third-party |
|---|---|---|---|
| `proofload-arbs` | per-user generators (uniform, Zipf, lists, weights, digits, UUIDs) as functions of the user number | core | none |
| `proofload-plan` | reads a `plan/1` YAML or JSON file into a simulation, and emits it as Kotlin | core, http, arbs | `snakeyaml-engine` |
| `proofload-plan-kafka` | lets a plan produce to Kafka topics | plan, kafka | through kafka |
| `proofload-openapi` | writes a plan from an OpenAPI document | plan | `snakeyaml-engine` |
| `proofload-contract` | writes a draft plan from Pelican endpoint values | plan, openapi | `pelican-core` (and its `jackson-core`) |
| `proofload-record` | reads a HAR recording into Kotlin source to edit and commit | core | `kotlinx-serialization-json` |

### Running and reporting

| Module | What it is | Proofload modules | Third-party |
|---|---|---|---|
| `proofload-cli` | `validate`, `preview`, `run`, `emit` and `from-openapi` from a shell, with the verdict as the exit code | core, plan, plan-kafka, openapi, engine, export | through plan-kafka and openapi |
| `proofload-mcp` | the CLI's operations as a stdio MCP server | cli, openapi, engine, export, baseline, report-html, report-github | `snakeyaml-engine`, and through cli |
| `proofload-report-html` | one self-contained HTML page | core | none |
| `proofload-report-github` | markdown, the Actions job summary and a Pages index | core | none |
| `proofload-baseline` | a run kept in a file and compared with the next | core | none |
| `proofload-export` | the run document (JSON), an HdrHistogram log and an OpenMetrics exposition | core | none |
| `proofload-otel` | measurements sent to an OpenTelemetry collector over OTLP/HTTP | core | OpenTelemetry SDK, with the JDK HTTP sender |

`proofload-cli` brings `kafka-clients` because it carries `proofload-plan-kafka`
so that a plan with Kafka steps can run from a shell. A project that only
reads plans of HTTP requests should depend on `proofload-plan` instead.

### In the build, not published

| Directory | What it is |
|---|---|
| `examples` | tests in which the modules are used together, and the sources `llms.txt` and `docs/for-agents.md` are checked against |
| `examples-java` | a load test written in Java, which the build compiles: [from-java.md](from-java.md) quotes it |
| `examples-scala` | a load test and a zio-test spec in Scala, which the build compiles and runs: [from-scala.md](from-scala.md) quotes them |
| `benchmarks` | measures Proofload's own overhead for [what-it-costs.md](what-it-costs.md); kept out of the tests and out of coverage |

`smoke/` is a separate Gradle build that depends on the published coordinates
rather than on the projects, so a broken POM or a missing jar fails at
resolution. Run `./gradlew publishToMavenLocal`, then
`cd smoke && ../gradlew test`.

## How the dependency claims are checked

Each published module has a test that reads the module's own runtime classpath
(passed to the test JVM by the module's build file) and fails on anything
outside a short allow-list. Some assert more than an allow-list:

| Module | Test |
|---|---|
| `proofload-core` | [NoThirdPartyDependenciesTest](../proofload-core/src/test/kotlin/io/github/matthewjones372/proofload/NoThirdPartyDependenciesTest.kt) |
| `proofload-engine` | [NoThirdPartyDependenciesTest](../proofload-engine/src/test/kotlin/io/github/matthewjones372/proofload/engine/NoThirdPartyDependenciesTest.kt) |
| `proofload-http` | [NoThirdPartyDependenciesTest](../proofload-http/src/test/kotlin/io/github/matthewjones372/proofload/http/NoThirdPartyDependenciesTest.kt): also that core is present |
| `proofload-websocket` | [NoThirdPartyDependenciesTest](../proofload-websocket/src/test/kotlin/io/github/matthewjones372/proofload/websocket/NoThirdPartyDependenciesTest.kt) |
| `proofload-grpc` | [NoTransportTest](../proofload-grpc/src/test/kotlin/io/github/matthewjones372/proofload/grpc/NoTransportTest.kt): no Netty, OkHttp or `grpc-core`, so the transport stays your choice |
| `proofload-grpc-dynamic` | [NoStubDependenciesTest](../proofload-grpc-dynamic/src/test/kotlin/io/github/matthewjones372/proofload/grpc/dynamic/NoStubDependenciesTest.kt) |
| `proofload-kafka` | [NoBrokerDependenciesTest](../proofload-kafka/src/test/kotlin/io/github/matthewjones372/proofload/kafka/NoBrokerDependenciesTest.kt): no embedded broker, Testcontainers or schema registry |
| `proofload-jdbc` | [NoThirdPartyDependenciesTest](../proofload-jdbc/src/test/kotlin/io/github/matthewjones372/proofload/jdbc/NoThirdPartyDependenciesTest.kt): no driver and no pool |
| `proofload-pelican` | [NoPekkoTest](../proofload-pelican/src/test/kotlin/io/github/matthewjones372/proofload/pelican/NoPekkoTest.kt): no Pekko or Akka |
| `proofload-junit5` | [NoSecondStackTest](../proofload-junit5/src/test/kotlin/io/github/matthewjones372/proofload/junit5/NoSecondStackTest.kt) |
| `proofload-kotest` | [NoSecondStackTest](../proofload-kotest/src/test/kotlin/io/github/matthewjones372/proofload/kotest/NoSecondStackTest.kt): also that `proofload-junit5` is absent |
| `proofload-zio-test` | [NoSecondStackTest](../proofload-zio-test/src/test/scala/io/github/matthewjones372/proofload/ziotest/NoSecondStackTest.scala): no ZIO or cats at runtime |
| `proofload-java` | [NoThirdPartyDependenciesTest](../proofload-java/src/test/kotlin/io/github/matthewjones372/proofload/java/NoThirdPartyDependenciesTest.kt) |
| `proofload-scala` | [OnlyTheScalaLibraryTest](../proofload-scala/src/test/scala/io/github/matthewjones372/proofload/scala/OnlyTheScalaLibraryTest.scala) |
| `proofload-arbs` | [NoThirdPartyDependenciesTest](../proofload-arbs/src/test/kotlin/io/github/matthewjones372/proofload/arbs/NoThirdPartyDependenciesTest.kt) |
| `proofload-plan` | [NoThirdPartyDependenciesTest](../proofload-plan/src/test/kotlin/io/github/matthewjones372/proofload/plan/NoThirdPartyDependenciesTest.kt) |
| `proofload-plan-kafka` | [NoThirdPartyDependenciesTest](../proofload-plan-kafka/src/test/kotlin/io/github/matthewjones372/proofload/plan/kafka/NoThirdPartyDependenciesTest.kt) |
| `proofload-openapi` | [NoThirdPartyDependenciesTest](../proofload-openapi/src/test/kotlin/io/github/matthewjones372/proofload/openapi/NoThirdPartyDependenciesTest.kt) |
| `proofload-contract` | [NoThirdPartyDependenciesTest](../proofload-contract/src/test/kotlin/io/github/matthewjones372/proofload/contract/NoThirdPartyDependenciesTest.kt) |
| `proofload-record` | [NoThirdPartyDependenciesTest](../proofload-record/src/test/kotlin/io/github/matthewjones372/proofload/record/NoThirdPartyDependenciesTest.kt) |
| `proofload-cli` | [NoThirdPartyDependenciesTest](../proofload-cli/src/test/kotlin/io/github/matthewjones372/proofload/cli/NoThirdPartyDependenciesTest.kt) |
| `proofload-mcp` | [NoThirdPartyDependenciesTest](../proofload-mcp/src/test/kotlin/io/github/matthewjones372/proofload/mcp/NoThirdPartyDependenciesTest.kt) |
| `proofload-report-html` | [NoThirdPartyDependenciesTest](../proofload-report-html/src/test/kotlin/io/github/matthewjones372/proofload/report/NoThirdPartyDependenciesTest.kt) |
| `proofload-report-github` | [NoThirdPartyDependenciesTest](../proofload-report-github/src/test/kotlin/io/github/matthewjones372/proofload/report/NoThirdPartyDependenciesTest.kt) |
| `proofload-baseline` | [NoDependenciesTest](../proofload-baseline/src/test/kotlin/io/github/matthewjones372/proofload/baseline/NoDependenciesTest.kt) |
| `proofload-export` | [NoThirdPartyDependenciesTest](../proofload-export/src/test/kotlin/io/github/matthewjones372/proofload/export/NoThirdPartyDependenciesTest.kt): HdrHistogram is on its test classpath only, as the oracle that reads the log back |
| `proofload-otel` | [NoGrpcStackTest](../proofload-otel/src/test/kotlin/io/github/matthewjones372/proofload/otel/NoGrpcStackTest.kt): no gRPC, Netty or OkHttp |

`proofload-kafka`'s produce and consume paths are also exercised without a
container: [FakeBrokerTest](../proofload-kafka/src/test/kotlin/io/github/matthewjones372/proofload/kafka/FakeBrokerTest.kt)
runs a real `KafkaProducer` and `KafkaConsumer` against a broker built from
`kafka-clients`' own protocol classes, over a socket.

This page is checked too. `ModulesDocTest` in `examples` fails when a module in
`settings.gradle.kts` is not named here, when a published module names no
dependency test, or when a test named here does not exist.
