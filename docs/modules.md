# Modules

`kestrel-core` depends on the Kotlin standard library and nothing else.
Anything with a third-party type in it is a leaf module beside it — an HTTP
client, a test framework, a reporting format — so a project takes the ones it
uses and inherits no stack it did not ask for.

| Module | What it is | Depends on |
|---|---|---|
| `kestrel-core` | scenarios, shapes, goals and results, all as values | **nothing** |
| `kestrel-engine` | runs a simulation on virtual threads, departures on a schedule | core |
| `kestrel-http` | HTTP steps on the JDK's `java.net.http` client | core |
| `kestrel-websocket` | WebSocket steps on the JDK's `java.net.http.WebSocket` | core |
| `kestrel-junit5` | a load test that is an ordinary `@Test` | core, engine, JUnit 5 |
| `kestrel-kotest` | the same, in a Kotest spec | core, engine |
| `kestrel-baseline` | a run kept in a file, so the next one can be compared to it | core |
| `kestrel-report-html` | one self-contained, interactive HTML page | core |
| `kestrel-report-github` | markdown, a job summary and a Pages directory | core |
| `kestrel-pelican` | [Pelican](https://github.com/matthewjones372/pelican) endpoints as steps | core, `pelican-core` |

Kotest is `compileOnly` in `kestrel-kotest`: a spec that uses the matchers
already has Kotest, and one that does not should not be handed twenty jars by a
load-testing library.

Two more directories are in the build and are not published. `examples` is
where every module meets, so that they compose is a test rather than a README
paragraph. `benchmarks` measures what this tool costs, and is kept out of the
coverage aggregation because measuring the tool is not testing it —
[what-it-costs.md](what-it-costs.md) is what it produces.

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
    implementation("io.github.matthewjones372:kestrel-baseline:$kestrelVersion")
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
| `kestrel-websocket` | [NoThirdPartyDependenciesTest](../kestrel-websocket/src/test/kotlin/io/github/matthewjones372/kestrel/websocket/NoThirdPartyDependenciesTest.kt) |
| `kestrel-junit5` | [NoSecondStackTest](../kestrel-junit5/src/test/kotlin/io/github/matthewjones372/kestrel/junit5/NoSecondStackTest.kt) |
| `kestrel-kotest` | [NoSecondStackTest](../kestrel-kotest/src/test/kotlin/io/github/matthewjones372/kestrel/kotest/NoSecondStackTest.kt) |
| `kestrel-baseline` | [NoDependenciesTest](../kestrel-baseline/src/test/kotlin/io/github/matthewjones372/kestrel/baseline/NoDependenciesTest.kt) |
| `kestrel-report-html` | [NoThirdPartyDependenciesTest](../kestrel-report-html/src/test/kotlin/io/github/matthewjones372/kestrel/report/NoThirdPartyDependenciesTest.kt) |
| `kestrel-report-github` | [NoThirdPartyDependenciesTest](../kestrel-report-github/src/test/kotlin/io/github/matthewjones372/kestrel/report/NoThirdPartyDependenciesTest.kt) |
| `kestrel-pelican` | [NoPekkoTest](../kestrel-pelican/src/test/kotlin/io/github/matthewjones372/kestrel/pelican/NoPekkoTest.kt) |

Each one reads the module's own `runtimeClasspath`, handed to the test JVM as a
system property by the module's build file, and fails on any entry outside a
short allow-list. A few say more than that: `kestrel-http` also asserts core is
exported, `kestrel-kotest` that `kestrel-junit5` is absent, and
`kestrel-pelican` that no part of Pekko reached the classpath.

This page is a test too. `ModulesDocTest` in `examples` fails when a module in
`settings.gradle.kts` is missing from the tables above, when a published module
names no test, or when a test named here has moved.

## Where the layering came from

The rule is not a style preference. A dependency in core is inherited by every
consumer of every other module, including the ones that only wanted a markdown
table — so core declares an interface and an adapter module carries the
library. [AGENTS.md](../AGENTS.md) states it; the tests above are what makes it
true.
