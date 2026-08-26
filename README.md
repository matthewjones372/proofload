<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/kestrel-mark-dark.svg">
  <img src="docs/assets/kestrel-mark-light.svg" width="88" height="88" alt="">
</picture>

# Kestrel

**Load testing for Kotlin, without the ceremony.** A scenario is an ordinary
Kotlin value: build it, inspect it, split it across files, run it.

</div>

> [!NOTE]
> Early, but it runs. Ten specs are built and green; nothing is released yet.
> See [AGENTS.md](AGENTS.md) before writing code.

```kotlin
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.engine.Kestrel
import io.github.matthewjones372.kestrel.http.http
import io.github.matthewjones372.kestrel.junit5.LoadTest
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.sessionKey
import io.github.matthewjones372.kestrel.step
import io.kotest.matchers.comparables.shouldBeLessThan
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

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
            .capture(orderId) { response -> response.header("location") },
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

No session parameter to name, no result to remember to return, no cast to read
one back, and no step name written twice: a key carries its type and a step
handle carries its name, so a rename is a compile error rather than a test that
passes against a step nobody runs. `at` is Gatling's `setUp`, `inject` and
`protocols` in one call, and what it returns is an ordinary value —
`simulation.profile.userCount()` is 3000 before anything has been sent.

Every virtual user starts with its own data, so a cache in front of the target
cannot answer for all of them:

```kotlin
val customer = sessionKey<String>("customer")

val checkout = scenario("checkout") {
    exec(browse, api.get("/products/{customer}"))    // filled from the session
}

checkout.at(50.perSecond, over = 1.minutes)
    .fedBy(feed(customer) { user -> "customer-$user" })
```

A feeder is a function of the user's number rather than a cursor over a source,
so there is nothing to lock on the path every request takes, nothing to run out
of, and user 4,001 gets the same data tomorrow as it did today.

A load shape is stages in order, and still a value — so it composes, and it
answers before a request leaves:

```kotlin
val soak = rampRate(from = 0.perSecond, to = 200.perSecond, over = 1.minutes)
    .then(hold(200.perSecond, over = 10.minutes))
    .thenRampTo(0.perSecond, over = 1.minutes)

checkout.injecting(soak).profile.userCount()   // 132,000, before anything is sent
```

Two latencies come back from every step. `serviceTime` is what the target took;
`responseTime` counts from the departure the profile promised, so a generator
that fell behind reports its own backlog rather than a fast target. When that
backlog is large enough to have moved a number, `result.fellBehind()` is true
and every report says so before it prints a percentile.

A timing carries the buckets it was read from, so it answers a percentile
nobody asked for while the run was going — `p999` among them, which is where
two JVM collectors that match to p99 come apart:

```kotlin
import io.github.matthewjones372.kestrel.Tail

result[placeOrder].serviceTime.percentile(99.95)      // any percentile, off the buckets

when (val tail = result[placeOrder].serviceTime.p999) {
    is Tail.Measured -> tail.duration
    is Tail.Absent -> tail.because   // "only 400 samples, and under 1000 …"
}
```

A run of four hundred requests has not measured one request in a thousand, so
`p999` answers with the reason rather than with a number nobody measured. A
goal can name the tail as readily — `p999(placeOrder) under 1.seconds`, beside
`p50`, `p95` and `p99` — and a run too short to have measured one misses that
goal carrying the same reason, rather than passing on a percentile it never
reached. The HTML report prints each step's tail with the 95% sampling
interval around it, which is the width a number resting on one request in a
thousand has.

## What this is for

Gatling is the reference point and the thing to be simpler than. Its scenario
DSL is Scala, its reports are a bundled web app, and running one in CI means
adopting its plugin and its conventions. The bet here is that most teams want
a much smaller slice: describe a scenario in Kotlin, run it from a test or a
`main`, get numbers that are honest about what they measured.

The three decisions that shape everything else, and are still open:

- **What a scenario is.** A value, not a builder that runs as it is called —
  so it can be composed, filtered, printed and compared before anything is
  sent.
- **What runs it.** Whatever engine the first spec argues for, behind an
  interface core declares, so the description does not belong to it.
- **What honest numbers mean here.** Coordinated omission is the default bug
  in this class of tool; where the design admits it, it gets said out loud
  rather than smoothed over.

## Building

```bash
./gradlew build          # tests, detekt, spotless, coverage
./gradlew spotlessApply  # and run this last, before you commit
```

## Layout

| Module | Depends on | For |
|---|---|---|
| `kestrel-core` | **nothing** | scenarios, profiles and results as values |
| `kestrel-engine` | core | virtual threads, departures on a schedule |
| `kestrel-http` | core | HTTP steps on `java.net.http` |
| `kestrel-junit5` | core, engine, JUnit | a load test that is a `@Test` |
| `kestrel-kotest` | core, engine | the same, in a Kotest spec |
| `kestrel-report-html` | core | one self-contained, interactive HTML file |
| `kestrel-report-github` | core | markdown, a job summary, a Pages index |
| `kestrel-pelican` | core, `pelican-core` | [Pelican](https://github.com/matthewjones372/pelican) endpoints as steps |

Every row is a test, not a promise: each module asserts its own runtime
classpath, so core cannot grow a dependency and the Kotest module cannot
quietly start needing the JUnit one.

Core depends on the Kotlin standard library and nothing else, and a test says
so. Everything with a third-party type in it becomes a leaf module beside it.

## License

Apache 2.0 — see [LICENSE](LICENSE).
