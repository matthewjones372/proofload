# Modules

`kestrel-core` depends on the Kotlin standard library and nothing else.
Anything with a third-party type in it is a leaf module beside it — an HTTP
client, a test framework, a reporting format — so a project takes the ones it
uses and inherits no stack it did not ask for.

| Module | What it is | Depends on |
|---|---|---|
| `kestrel-core` | scenarios, profiles, goals and results, all as values, and the `Engine` that runs one | **nothing** |
| `kestrel-engine` | runs a simulation on virtual threads, departures on a schedule | core |
| `kestrel-http` | HTTP steps on the JDK's `java.net.http` client, swappable for another, and server-sent event streams | core |
| `kestrel-websocket` | WebSocket steps on the JDK's `java.net.http.WebSocket` | core |
| `kestrel-grpc` | gRPC steps over a caller's own stubs, named by the method descriptor | core, `grpc-api` |
| `kestrel-grpc-dynamic` | the same calls from a descriptor set or the target's own reflection, for a caller with no generated stubs | core, grpc, `protobuf-java`, `grpc-services` |
| `kestrel-jdbc` | database steps over a caller's own `DataSource`, the pool wait counted apart from the query | core |
| `kestrel-kafka` | produce steps, and completions read off another topic | core, `kafka-clients` |
| `kestrel-java` | the same values built from Java: static factories over `Rate`, `StepName` and `Share`, a builder where Kotlin has a lambda, and `java.time.Duration` throughout | core, engine, http |
| `kestrel-scala` | the same values built from Scala 3: `FiniteDuration` both ways, `perSecond`, `sessionKey[T]`, and the scenario the Kotlin DSL builds | core, engine, http, java, `scala3-library` |
| `kestrel-junit5` | a load test that is an ordinary `@Test` | core, engine, JUnit 5 |
| `kestrel-kotest` | the same, in a Kotest spec | core, engine |
| `kestrel-arbs` | generators shaped like traffic — cardinality and skew, as a function of the user's number | core |
| `kestrel-baseline` | a run kept in a file, so the next one can be compared to it | core |
| `kestrel-export` | a run's measurements in the formats other tools already read | core |
| `kestrel-otel` | the same measurements, sent to an OpenTelemetry collector | core, the OTel SDK |
| `kestrel-record` | a HAR recording read into a Kotlin scenario you edit and commit | core, `kotlinx-serialization-json` |
| `kestrel-plan` | a plan written down, read and lowered into the values the DSL builds, and printed back as Kotlin | core, http, `snakeyaml-engine` |
| `kestrel-plan-kafka` | lowers a plan's produce steps onto a cluster, so `kestrel-plan` carries no broker | core, plan, kafka |
| `kestrel-cli` | `validate`, `preview`, `run` and `emit` from a shell, with the verdict as the exit code | core, plan, engine, export |
| `kestrel-mcp` | the same calls over MCP, for a caller that is a program | core, cli, `snakeyaml-engine` |
| `kestrel-openapi` | an OpenAPI document read into a plan you edit and commit | core, plan, `snakeyaml-engine` |
| `kestrel-contract` | endpoint values read into a plan you edit and commit | core, plan, openapi, `pelican-core` |
| `kestrel-report-html` | one self-contained, interactive HTML page | core |
| `kestrel-report-github` | markdown, a job summary and a Pages directory | core |
| `kestrel-pelican` | [Pelican](https://github.com/matthewjones372/pelican) endpoints as steps | core, `pelican-core` |

Kotest is `compileOnly` in `kestrel-kotest`: a spec that uses the matchers
already has Kotest, and one that does not should not be handed twenty jars by a
load-testing library.

Three more directories are in the build and are not published. `examples` is
where every module meets, so that they compose is a test rather than a README
paragraph. `examples-java` is one load test written in Java: `apiCheck` records
`kestrel-java`'s Kotlin surface and cannot see whether that surface is callable
from Java, and only a Java compiler knows — [from-java.md](from-java.md) is the
page it backs. `benchmarks` measures what this tool costs, and is kept out of the
coverage aggregation because measuring the tool is not testing it —
[what-it-costs.md](what-it-costs.md) is what it produces.

`smoke/` is not in the build at all. It is a Gradle build of its own that
depends on the coordinates in the next section rather than on the projects that
produce them, so a wrong POM or a missing jar fails at resolution here instead
of being found by a stranger. `./gradlew publishToMavenLocal` and then
`cd smoke && ../gradlew test` is what checks the table below is usable.

## Taking them

```kotlin
// build.gradle.kts
dependencies {
    // Whatever you have published or built with ./gradlew publishToMavenLocal;
    // nothing is on Maven Central yet.
    implementation("io.github.matthewjones372:kestrel-core:$kestrelVersion")
    implementation("io.github.matthewjones372:kestrel-engine:$kestrelVersion")
    implementation("io.github.matthewjones372:kestrel-http:$kestrelVersion")
    implementation("io.github.matthewjones372:kestrel-websocket:$kestrelVersion")

    // One of these two, for the framework you already run tests in.
    testImplementation("io.github.matthewjones372:kestrel-junit5:$kestrelVersion")
    testImplementation("io.github.matthewjones372:kestrel-kotest:$kestrelVersion")

    // As you need them.
    implementation("io.github.matthewjones372:kestrel-java:$kestrelVersion")
    implementation("io.github.matthewjones372:kestrel-scala:$kestrelVersion")
    implementation("io.github.matthewjones372:kestrel-arbs:$kestrelVersion")
    implementation("io.github.matthewjones372:kestrel-jdbc:$kestrelVersion")
    implementation("io.github.matthewjones372:kestrel-baseline:$kestrelVersion")
    implementation("io.github.matthewjones372:kestrel-export:$kestrelVersion")
    implementation("io.github.matthewjones372:kestrel-plan:$kestrelVersion")
    implementation("io.github.matthewjones372:kestrel-otel:$kestrelVersion")
    implementation("io.github.matthewjones372:kestrel-report-html:$kestrelVersion")
    implementation("io.github.matthewjones372:kestrel-report-github:$kestrelVersion")
    implementation("io.github.matthewjones372:kestrel-pelican:$kestrelVersion")
}
```

## Every row above is a test

Each module asserts its own runtime classpath from its own test source, so a
dependency added to core fails the build rather than being argued about, and
the Kotest module cannot quietly start needing the JUnit one.

| Module | Proven by |
|---|---|
| `kestrel-core` | [NoThirdPartyDependenciesTest](../kestrel-core/src/test/kotlin/io/github/matthewjones372/kestrel/NoThirdPartyDependenciesTest.kt) |
| `kestrel-engine` | [NoThirdPartyDependenciesTest](../kestrel-engine/src/test/kotlin/io/github/matthewjones372/kestrel/engine/NoThirdPartyDependenciesTest.kt) |
| `kestrel-http` | [NoThirdPartyDependenciesTest](../kestrel-http/src/test/kotlin/io/github/matthewjones372/kestrel/http/NoThirdPartyDependenciesTest.kt) |
| `kestrel-jdbc` | [NoThirdPartyDependenciesTest](../kestrel-jdbc/src/test/kotlin/io/github/matthewjones372/kestrel/jdbc/NoThirdPartyDependenciesTest.kt) |
| `kestrel-record` | [NoThirdPartyDependenciesTest](../kestrel-record/src/test/kotlin/io/github/matthewjones372/kestrel/record/NoThirdPartyDependenciesTest.kt) |
| `kestrel-websocket` | [NoThirdPartyDependenciesTest](../kestrel-websocket/src/test/kotlin/io/github/matthewjones372/kestrel/websocket/NoThirdPartyDependenciesTest.kt) |
| `kestrel-java` | [NoThirdPartyDependenciesTest](../kestrel-java/src/test/kotlin/io/github/matthewjones372/kestrel/java/NoThirdPartyDependenciesTest.kt) |
| `kestrel-scala` | [OnlyTheScalaLibraryTest](../kestrel-scala/src/test/scala/io/github/matthewjones372/kestrel/scala/OnlyTheScalaLibraryTest.scala) |
| `kestrel-junit5` | [NoSecondStackTest](../kestrel-junit5/src/test/kotlin/io/github/matthewjones372/kestrel/junit5/NoSecondStackTest.kt) |
| `kestrel-kotest` | [NoSecondStackTest](../kestrel-kotest/src/test/kotlin/io/github/matthewjones372/kestrel/kotest/NoSecondStackTest.kt) |
| `kestrel-arbs` | [NoThirdPartyDependenciesTest](../kestrel-arbs/src/test/kotlin/io/github/matthewjones372/kestrel/arbs/NoThirdPartyDependenciesTest.kt) |
| `kestrel-baseline` | [NoDependenciesTest](../kestrel-baseline/src/test/kotlin/io/github/matthewjones372/kestrel/baseline/NoDependenciesTest.kt) |
| `kestrel-export` | [NoThirdPartyDependenciesTest](../kestrel-export/src/test/kotlin/io/github/matthewjones372/kestrel/export/NoThirdPartyDependenciesTest.kt) |
| `kestrel-plan` | [NoThirdPartyDependenciesTest](../kestrel-plan/src/test/kotlin/io/github/matthewjones372/kestrel/plan/NoThirdPartyDependenciesTest.kt) |
| `kestrel-plan-kafka` | [NoThirdPartyDependenciesTest](../kestrel-plan-kafka/src/test/kotlin/io/github/matthewjones372/kestrel/plan/kafka/NoThirdPartyDependenciesTest.kt) |
| `kestrel-openapi` | [NoThirdPartyDependenciesTest](../kestrel-openapi/src/test/kotlin/io/github/matthewjones372/kestrel/openapi/NoThirdPartyDependenciesTest.kt) |
| `kestrel-contract` | [NoThirdPartyDependenciesTest](../kestrel-contract/src/test/kotlin/io/github/matthewjones372/kestrel/contract/NoThirdPartyDependenciesTest.kt) |
| `kestrel-cli` | [NoThirdPartyDependenciesTest](../kestrel-cli/src/test/kotlin/io/github/matthewjones372/kestrel/cli/NoThirdPartyDependenciesTest.kt) |
| `kestrel-mcp` | [NoThirdPartyDependenciesTest](../kestrel-mcp/src/test/kotlin/io/github/matthewjones372/kestrel/mcp/NoThirdPartyDependenciesTest.kt) |
| `kestrel-report-html` | [NoThirdPartyDependenciesTest](../kestrel-report-html/src/test/kotlin/io/github/matthewjones372/kestrel/report/NoThirdPartyDependenciesTest.kt) |
| `kestrel-report-github` | [NoThirdPartyDependenciesTest](../kestrel-report-github/src/test/kotlin/io/github/matthewjones372/kestrel/report/NoThirdPartyDependenciesTest.kt) |
| `kestrel-pelican` | [NoPekkoTest](../kestrel-pelican/src/test/kotlin/io/github/matthewjones372/kestrel/pelican/NoPekkoTest.kt) |
| `kestrel-otel` | [NoGrpcStackTest](../kestrel-otel/src/test/kotlin/io/github/matthewjones372/kestrel/otel/NoGrpcStackTest.kt) |
| `kestrel-grpc` | [NoTransportTest](../kestrel-grpc/src/test/kotlin/io/github/matthewjones372/kestrel/grpc/NoTransportTest.kt) |
| `kestrel-grpc-dynamic` | [NoStubDependenciesTest](../kestrel-grpc-dynamic/src/test/kotlin/io/github/matthewjones372/kestrel/grpc/dynamic/NoStubDependenciesTest.kt) |
| `kestrel-kafka` | [NoBrokerDependenciesTest](../kestrel-kafka/src/test/kotlin/io/github/matthewjones372/kestrel/kafka/NoBrokerDependenciesTest.kt) |

Each one reads the module's own `runtimeClasspath`, handed to the test JVM as a
system property by the module's build file, and fails on any entry outside a
short allow-list. A few say more than that: `kestrel-http` also asserts core is
exported, `kestrel-kotest` that `kestrel-junit5` is absent, and
`kestrel-pelican` that no part of Pekko reached the classpath. `kestrel-export`
is the pointed case: HdrHistogram owns the log format it writes and would write
it in one call, and it sits on that module's *test* classpath instead — as the
oracle that reads the output back, rather than on the classpath of everyone who
wanted one file out of a run. `kestrel-otel` is the other side of the same
argument: it carries the OpenTelemetry SDK because that is what it is for, and
its test says *which* dependency arrived — OTLP over HTTP, so no gRPC runtime
and no Netty, because a load test's own process is the last place to put a
second networking stack. `kestrel-grpc` makes the third version of the claim:
`grpc-api` and no transport, so `grpc-netty-shaded` or `grpc-okhttp` stays the
caller's choice — it is the thing that most decides what a gRPC run can drive,
and picking one here would decide it for everybody silently. `kestrel-kafka`
says what did *not* arrive: no broker, embedded or containerised, and no schema
registry — `io.confluent:kafka-avro-serializer` is not on Maven Central, so
depending on it would force a `packages.confluent.io` declaration on every
consumer and break the smoke project below, which resolves from
`mavenCentral()` on purpose. Its wire is proved rather than assumed:
`FakeBrokerTest` stands up a broker built from `kafka-clients`' own protocol
classes and drives a real `KafkaProducer` and a real `KafkaConsumer` at it over
a socket, so the accumulator, the sender thread and the ack path — the parts no
mock models — run inside `./gradlew build` with no container and no Docker.

This page is a test too. `ModulesDocTest` in `examples` fails when a module in
`settings.gradle.kts` is missing from the tables above, when a published module
names no test, or when a test named here has moved.

## Where the layering came from

The rule is not a style preference. A dependency in core is inherited by every
consumer of every other module, including the ones that only wanted a markdown
table — so core declares an interface and an adapter module carries the
library. [AGENTS.md](../AGENTS.md) states it; the tests above are what makes it
true.
