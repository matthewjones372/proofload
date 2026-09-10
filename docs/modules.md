# Modules

`proofload-core` depends on the Kotlin standard library and nothing else.
Anything with a third-party type in it is a leaf module beside it: an HTTP
client, a test framework, a reporting format. A project takes the ones it
uses and inherits no stack it did not ask for.

| Module | What it is | Depends on |
|---|---|---|
| `proofload-core` | scenarios, profiles, goals and results, all as values, and the `Engine` that runs one | **nothing** |
| `proofload-engine` | runs a simulation on virtual threads, departures on a schedule | core |
| `proofload-http` | HTTP steps on the JDK's `java.net.http` client, swappable for another, and server-sent event streams | core |
| `proofload-websocket` | WebSocket steps on the JDK's `java.net.http.WebSocket` | core |
| `proofload-grpc` | gRPC steps over a caller's own stubs, named by the method descriptor | core, `grpc-api` |
| `proofload-grpc-dynamic` | the same calls from a descriptor set or the target's own reflection, for a caller with no generated stubs | core, grpc, `protobuf-java`, `grpc-services` |
| `proofload-jdbc` | database steps over a caller's own `DataSource`, the pool wait counted apart from the query | core |
| `proofload-kafka` | produce steps, and completions read off another topic | core, `kafka-clients` |
| `proofload-java` | the same values built from Java: static factories over `Rate`, `StepName` and `Share`, a builder where Kotlin has a lambda, and `java.time.Duration` throughout | core, engine, http |
| `proofload-scala` | the same values built from Scala 3: `FiniteDuration` both ways, `perSecond`, `sessionKey[T]`, and the scenario the Kotlin DSL builds | core, engine, http, java, `scala3-library` |
| `proofload-junit5` | a load test that is an ordinary `@Test` | core, engine, JUnit 5 |
| `proofload-kotest` | the same, in a Kotest spec | core, engine |
| `proofload-zio-test` | the same again, in a zio-test spec, sent on the blocking executor rather than the pool the fiber is on | core, engine, http, java, scala |
| `proofload-arbs` | generators shaped like traffic: cardinality and skew, as a function of the user's number | core |
| `proofload-baseline` | a run kept in a file, so the next one can be compared to it | core |
| `proofload-export` | a run's measurements in the formats other tools already read | core |
| `proofload-otel` | the same measurements, sent to an OpenTelemetry collector | core, the OTel SDK |
| `proofload-record` | a HAR recording read into a Kotlin scenario you edit and commit | core, `kotlinx-serialization-json` |
| `proofload-plan` | a plan written down, read and lowered into the values the DSL builds, and printed back as Kotlin | core, http, `snakeyaml-engine` |
| `proofload-plan-kafka` | lowers a plan's produce steps onto a cluster, so `proofload-plan` carries no broker | core, plan, kafka |
| `proofload-cli` | `validate`, `preview`, `run` and `emit` from a shell, with the verdict as the exit code | core, plan, engine, export |
| `proofload-mcp` | the same calls over MCP, for a caller that is a program | core, cli, `snakeyaml-engine` |
| `proofload-openapi` | an OpenAPI document read into a plan you edit and commit | core, plan, `snakeyaml-engine` |
| `proofload-contract` | endpoint values read into a plan you edit and commit | core, plan, openapi, `pelican-core` |
| `proofload-report-html` | one self-contained, interactive HTML page | core |
| `proofload-report-github` | markdown, a job summary and a Pages directory | core |
| `proofload-pelican` | [Pelican](https://github.com/matthewjones372/pelican) endpoints as steps | core, `pelican-core` |

**Three of these have a measured ceiling and four do not.**
[what-it-costs.md](what-it-costs.md#what-has-a-number-and-what-has-none) says
which, and what each number leaves out.

Kotest is `compileOnly` in `proofload-kotest`: a spec that uses the matchers
already has Kotest, and one that does not should not be handed twenty jars by a
load-testing library. zio-test is `compileOnly` in `proofload-zio-test` for the
same reason and one more: nothing there ships an effect runtime to a project
that asked for a load test, which its dependency test states.

Three more directories are in the build and are not published. `examples` is
where every module meets, so that they compose is a test rather than a README
paragraph. `examples-java` is one load test written in Java: `apiCheck` records
`proofload-java`'s Kotlin surface and cannot see whether that surface is callable
from Java, and only a Java compiler knows. [from-java.md](from-java.md) is the
page it backs. `examples-scala` is the same gate one step further out:
`proofload-scala` has no `.api` dump at all, because what BCV records of a Scala
module is compiler-generated names no caller can type. It carries a zio-test
spec beside the sample, which the build runs rather than only compiles.
[from-scala.md](from-scala.md) is the page both back. `benchmarks` measures what this tool costs, and is kept out of the
coverage aggregation because measuring the tool is not testing it.
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
    // `0.1.0-rc4` is the current release on Maven Central; anything newer is
    // what you built with ./gradlew publishToMavenLocal.
    implementation("io.github.matthewjones372:proofload-core:$proofloadVersion")
    implementation("io.github.matthewjones372:proofload-engine:$proofloadVersion")
    implementation("io.github.matthewjones372:proofload-http:$proofloadVersion")
    implementation("io.github.matthewjones372:proofload-websocket:$proofloadVersion")

    // One of these three, for the framework you already run tests in.
    testImplementation("io.github.matthewjones372:proofload-junit5:$proofloadVersion")
    testImplementation("io.github.matthewjones372:proofload-kotest:$proofloadVersion")
    testImplementation("io.github.matthewjones372:proofload-zio-test:$proofloadVersion")

    // As you need them.
    implementation("io.github.matthewjones372:proofload-java:$proofloadVersion")
    implementation("io.github.matthewjones372:proofload-scala:$proofloadVersion")
    implementation("io.github.matthewjones372:proofload-arbs:$proofloadVersion")
    implementation("io.github.matthewjones372:proofload-jdbc:$proofloadVersion")
    implementation("io.github.matthewjones372:proofload-baseline:$proofloadVersion")
    implementation("io.github.matthewjones372:proofload-export:$proofloadVersion")
    implementation("io.github.matthewjones372:proofload-plan:$proofloadVersion")
    implementation("io.github.matthewjones372:proofload-otel:$proofloadVersion")
    implementation("io.github.matthewjones372:proofload-report-html:$proofloadVersion")
    implementation("io.github.matthewjones372:proofload-report-github:$proofloadVersion")
    implementation("io.github.matthewjones372:proofload-pelican:$proofloadVersion")
}
```

## Every row above is a test

Each module asserts its own runtime classpath from its own test source, so a
dependency added to core fails the build rather than being argued about, and
the Kotest module cannot quietly start needing the JUnit one.

| Module | Proven by |
|---|---|
| `proofload-core` | [NoThirdPartyDependenciesTest](../proofload-core/src/test/kotlin/io/github/matthewjones372/proofload/NoThirdPartyDependenciesTest.kt) |
| `proofload-engine` | [NoThirdPartyDependenciesTest](../proofload-engine/src/test/kotlin/io/github/matthewjones372/proofload/engine/NoThirdPartyDependenciesTest.kt) |
| `proofload-http` | [NoThirdPartyDependenciesTest](../proofload-http/src/test/kotlin/io/github/matthewjones372/proofload/http/NoThirdPartyDependenciesTest.kt) |
| `proofload-jdbc` | [NoThirdPartyDependenciesTest](../proofload-jdbc/src/test/kotlin/io/github/matthewjones372/proofload/jdbc/NoThirdPartyDependenciesTest.kt) |
| `proofload-record` | [NoThirdPartyDependenciesTest](../proofload-record/src/test/kotlin/io/github/matthewjones372/proofload/record/NoThirdPartyDependenciesTest.kt) |
| `proofload-websocket` | [NoThirdPartyDependenciesTest](../proofload-websocket/src/test/kotlin/io/github/matthewjones372/proofload/websocket/NoThirdPartyDependenciesTest.kt) |
| `proofload-java` | [NoThirdPartyDependenciesTest](../proofload-java/src/test/kotlin/io/github/matthewjones372/proofload/java/NoThirdPartyDependenciesTest.kt) |
| `proofload-scala` | [OnlyTheScalaLibraryTest](../proofload-scala/src/test/scala/io/github/matthewjones372/proofload/scala/OnlyTheScalaLibraryTest.scala) |
| `proofload-junit5` | [NoSecondStackTest](../proofload-junit5/src/test/kotlin/io/github/matthewjones372/proofload/junit5/NoSecondStackTest.kt) |
| `proofload-kotest` | [NoSecondStackTest](../proofload-kotest/src/test/kotlin/io/github/matthewjones372/proofload/kotest/NoSecondStackTest.kt) |
| `proofload-zio-test` | [NoSecondStackTest](../proofload-zio-test/src/test/scala/io/github/matthewjones372/proofload/ziotest/NoSecondStackTest.scala) |
| `proofload-arbs` | [NoThirdPartyDependenciesTest](../proofload-arbs/src/test/kotlin/io/github/matthewjones372/proofload/arbs/NoThirdPartyDependenciesTest.kt) |
| `proofload-baseline` | [NoDependenciesTest](../proofload-baseline/src/test/kotlin/io/github/matthewjones372/proofload/baseline/NoDependenciesTest.kt) |
| `proofload-export` | [NoThirdPartyDependenciesTest](../proofload-export/src/test/kotlin/io/github/matthewjones372/proofload/export/NoThirdPartyDependenciesTest.kt) |
| `proofload-plan` | [NoThirdPartyDependenciesTest](../proofload-plan/src/test/kotlin/io/github/matthewjones372/proofload/plan/NoThirdPartyDependenciesTest.kt) |
| `proofload-plan-kafka` | [NoThirdPartyDependenciesTest](../proofload-plan-kafka/src/test/kotlin/io/github/matthewjones372/proofload/plan/kafka/NoThirdPartyDependenciesTest.kt) |
| `proofload-openapi` | [NoThirdPartyDependenciesTest](../proofload-openapi/src/test/kotlin/io/github/matthewjones372/proofload/openapi/NoThirdPartyDependenciesTest.kt) |
| `proofload-contract` | [NoThirdPartyDependenciesTest](../proofload-contract/src/test/kotlin/io/github/matthewjones372/proofload/contract/NoThirdPartyDependenciesTest.kt) |
| `proofload-cli` | [NoThirdPartyDependenciesTest](../proofload-cli/src/test/kotlin/io/github/matthewjones372/proofload/cli/NoThirdPartyDependenciesTest.kt) |
| `proofload-mcp` | [NoThirdPartyDependenciesTest](../proofload-mcp/src/test/kotlin/io/github/matthewjones372/proofload/mcp/NoThirdPartyDependenciesTest.kt) |
| `proofload-report-html` | [NoThirdPartyDependenciesTest](../proofload-report-html/src/test/kotlin/io/github/matthewjones372/proofload/report/NoThirdPartyDependenciesTest.kt) |
| `proofload-report-github` | [NoThirdPartyDependenciesTest](../proofload-report-github/src/test/kotlin/io/github/matthewjones372/proofload/report/NoThirdPartyDependenciesTest.kt) |
| `proofload-pelican` | [NoPekkoTest](../proofload-pelican/src/test/kotlin/io/github/matthewjones372/proofload/pelican/NoPekkoTest.kt) |
| `proofload-otel` | [NoGrpcStackTest](../proofload-otel/src/test/kotlin/io/github/matthewjones372/proofload/otel/NoGrpcStackTest.kt) |
| `proofload-grpc` | [NoTransportTest](../proofload-grpc/src/test/kotlin/io/github/matthewjones372/proofload/grpc/NoTransportTest.kt) |
| `proofload-grpc-dynamic` | [NoStubDependenciesTest](../proofload-grpc-dynamic/src/test/kotlin/io/github/matthewjones372/proofload/grpc/dynamic/NoStubDependenciesTest.kt) |
| `proofload-kafka` | [NoBrokerDependenciesTest](../proofload-kafka/src/test/kotlin/io/github/matthewjones372/proofload/kafka/NoBrokerDependenciesTest.kt) |

Each one reads the module's own `runtimeClasspath`, handed to the test JVM as a
system property by the module's build file, and fails on any entry outside a
short allow-list. A few say more than that: `proofload-http` also asserts core is
exported, `proofload-kotest` that `proofload-junit5` is absent, and
`proofload-pelican` that no part of Pekko reached the classpath. `proofload-export`
is the pointed case: HdrHistogram owns the log format it writes and would write
it in one call, and it sits on that module's *test* classpath instead, as the
oracle that reads the output back, rather than on the classpath of everyone who
wanted one file out of a run. `proofload-otel` is the other side of the same
argument: it carries the OpenTelemetry SDK because that is what it is for, and
its test says *which* dependency arrived: OTLP over HTTP, so no gRPC runtime
and no Netty, because a load test's own process is the last place to put a
second networking stack. `proofload-grpc` makes the third version of the claim:
`grpc-api` and no transport, so `grpc-netty-shaded` or `grpc-okhttp` stays the
caller's choice, since it is the thing that most decides what a gRPC run can drive,
and picking one here would decide it for everybody silently. `proofload-kafka`
says what did *not* arrive: no broker, embedded or containerised, and no schema
registry. `io.confluent:kafka-avro-serializer` is not on Maven Central, so
depending on it would force a `packages.confluent.io` declaration on every
consumer and break the smoke project below, which resolves from
`mavenCentral()` on purpose. Its wire is proved rather than assumed:
`FakeBrokerTest` stands up a broker built from `kafka-clients`' own protocol
classes and drives a real `KafkaProducer` and a real `KafkaConsumer` at it over
a socket, so the accumulator, the sender thread and the ack path, the parts no
mock models, run inside `./gradlew build` with no container and no Docker.

This page is a test too. `ModulesDocTest` in `examples` fails when a module in
`settings.gradle.kts` is missing from the tables above, when a published module
names no test, or when a test named here has moved.

## Where the layering came from

The rule is not a style preference. A dependency in core is inherited by every
consumer of every other module, including the ones that only wanted a markdown
table, so core declares an interface and an adapter module carries the
library. [AGENTS.md](../AGENTS.md) states it; the tests above are what makes it
true.
