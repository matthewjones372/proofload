# Getting started

This page takes you from an empty test source set to a load test that runs,
checks its own schedule and writes a report.

## Requirements

- **JDK 21 or later.** Users run on virtual threads. The build is tested on JDK
  21, 23 and 25.
- **Kotlin**, for the examples here. The library is compiled with Kotlin
  2.4.20. Java and Scala 3 have their own entry points:
  [from-java.md](from-java.md), [from-scala.md](from-scala.md).
- A target to send load to. The examples use `https://orders.internal`; a JDK
  `HttpServer` in the same test is enough to try it out, as the repository's
  own [CheckoutLoadTest](../examples/src/test/kotlin/io/github/matthewjones372/proofload/examples/CheckoutLoadTest.kt)
  does.

## Dependencies

```kotlin
// build.gradle.kts
dependencies {
    testImplementation("io.github.matthewjones372:proofload-http:0.1.0-rc4")
    testImplementation("io.github.matthewjones372:proofload-junit5:0.1.0-rc4")
    testImplementation("io.github.matthewjones372:proofload-report-html:0.1.0-rc4")
}

tasks.test {
    useJUnitPlatform()
}
```

`0.1.0-rc4` is the current release on Maven Central; [versions.md](versions.md)
says what has changed on `main` since. For Kotest or zio-test instead of JUnit,
see [running.md](running.md). Other protocols and outputs are separate modules:
[modules.md](modules.md).

## A first test

```kotlin
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.engine.Proofload
import io.github.matthewjones372.proofload.expecting
import io.github.matthewjones372.proofload.http.http
import io.github.matthewjones372.proofload.junit5.LoadTest
import io.github.matthewjones372.proofload.keptSchedule
import io.github.matthewjones372.proofload.lostGround
import io.github.matthewjones372.proofload.p99
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.report.writeHtmlReport
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.sessionKey
import io.github.matthewjones372.proofload.step
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private val orderId = sessionKey<String>("orderId")
private val browse = step("browse")
private val placeOrder = step("place order")
private val confirm = step("confirm")

private val api = http.baseUrl("https://orders.internal")

private val checkout = scenario("checkout") {
    exec(browse, api.get("/products"))
    exec(
        placeOrder,
        api.post("/orders")
            .body("""{"cart":"1 anvil"}""")
            .expecting(201)
            .capture(orderId) { it.header("x-order-id") },
    )
    exec(confirm, api.get("/orders/{orderId}"))
}

class CheckoutLoadTest {

    @LoadTest
    fun `checkout holds up at fifty a second`(proofload: Proofload) {
        val result = proofload.run(
            checkout.at(50.perSecond, over = 1.minutes)
                .expecting(p99(placeOrder) under 200.milliseconds, keptSchedule),
        )

        result.writeHtmlReport(Path.of("build/reports/proofload/checkout.html"))

        assertFalse(result.lostGround(), "the generator did not deliver the planned load")
        assertTrue(result.metEveryGoal, result.verdicts.joinToString("\n"))
        assertEquals(0L, result.failed)
    }
}
```

What each part does:

- `step(...)` and `sessionKey<String>(...)` declare names once. The same values
  are used to build the scenario and to read the result, so renaming a step is a
  compile error rather than an assertion about a step that no longer runs.
- `scenario { ... }` is a value. Nothing is sent when it is built.
- `expecting(201)`, `capture(...)` and `{orderId}` belong to the requests. A
  request with the wrong status fails, with `HttpStatus(code)` as its reason,
  and that user's journey stops there.
- `at(50.perSecond, over = 1.minutes)` is an open workload: 3,000 users, one
  every 20 ms, each departing on time whether or not earlier users have
  finished.
- `expecting(...)` on the simulation states the goals. `keptSchedule` is the
  goal that the generator itself kept up; it is not added for you.
- `lostGround()` checks that departures were not late by more than the planned
  gap between them, which `keptSchedule` does not check.

## Running it

`./gradlew test` runs it like any other test. While it runs, it prints its
plan and a progress line every few seconds:

```
proofload: checkout — 3,000 users over 1m, 3 steps each
proofload: 00:05  departed 250  in flight 3  behind 88.033us  00:55 left
```

`behind` is how late the most recent departures were. If it keeps growing, the
generator is not offering the rate you asked for.

## Reading the result

Open `build/reports/proofload/checkout.html`. Read it in this order:

1. **Did the generator keep up?** A "behind schedule" banner, or a missed "the
   generator keeps its schedule" goal, means response times include time spent
   queued inside the generator. Fix that first: lower the rate, give the
   generator more machine, or split the run across injectors.
2. **Did requests fail?** Failures are listed by reason. A step whose requests
   failed fast can have a deceptively low p99, because a step's percentiles
   include failed requests.
3. **Did the goals hold?** Each goal shows what was measured and, for a miss, by
   how much.
4. **Is the run long enough?** `p999` is reported only with at least 1,000
   samples. The steady-state section says whether the run settled.

[measurement.md](measurement.md) explains each number and what a clean run does
and does not establish.

## Next

- [Shape the workload](workload.md): ramps, stages, Poisson arrivals, think time
  and fixed populations.
- [Give each user its own data](data.md).
- [Other protocols](protocols.md) and [other starting points](plans.md).
- [Keep a baseline and compare runs](reports-and-baselines.md).
