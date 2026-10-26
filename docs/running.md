# Running a load test

A simulation is a value. Running it needs an engine, and Proofload's runs it on
virtual threads. You can hand it a runner from a test framework or call it from
a `main`; the scenario and the result are the same either way.

This page covers where a run happens: test frameworks, a plain `main`, the
machine lock that stops two runs measuring each other, progress output, and
what to check before a long run. [getting-started.md](getting-started.md) has
the dependencies.

## JUnit

`@LoadTest` is a JUnit Jupiter `@Test` with a `Proofload` parameter. There is no
base class and nothing to register. It carries a one-hour timeout.

```kotlin
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.engine.Proofload
import io.github.matthewjones372.proofload.expecting
import io.github.matthewjones372.proofload.http.http
import io.github.matthewjones372.proofload.junit5.LoadTest
import io.github.matthewjones372.proofload.keptSchedule
import io.github.matthewjones372.proofload.p99
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.step
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private val browse = step("browse")
private val placeOrder = step("place order")

class CheckoutLoadTest {

    private val api = http.baseUrl("https://orders.internal")

    private val checkout = scenario("checkout") {
        exec(browse, api.get("/products"))
        exec(placeOrder, api.post("/orders").body("""{"cart":"1 anvil"}""").expecting(201))
    }

    @LoadTest
    fun `checkout holds up at fifty a second`(proofload: Proofload) {
        val result = proofload.run(
            checkout.at(50.perSecond, over = 1.minutes)
                .expecting(p99(placeOrder) under 200.milliseconds, keptSchedule),
        )

        assertTrue(result.metEveryGoal, result.verdicts.joinToString())
        assertEquals(0L, result.failed)
    }
}
```

The test framework modules do not fail a test because the generator fell
behind. Declaring `keptSchedule` and asserting `metEveryGoal`, as above, is how
a test makes that a failure; [measurement.md](measurement.md#the-schedule-checks)
explains what it checks and what it does not.

## Kotest

`proofload()` is a suspend function that returns a runner. There is no spec base
class and nothing to register. The runner it returns prints no progress lines.

```kotlin
import io.github.matthewjones372.proofload.kotest.proofload
import io.kotest.core.spec.style.StringSpec

class CheckoutSpec : StringSpec({

    "checkout holds up at fifty a second" {
        val result = proofload().run(checkout.at(50.perSecond, over = 1.minutes))

        result[placeOrder].responseTime.p99 shouldBeLessThan 200.milliseconds
    }
})
```

## zio-test

`ProofloadSpec` is a `ZIOSpecDefault` that already carries what a load spec
needs, so what is left of the spec is the measurement:

```scala
object CheckoutSpec extends ProofloadSpec:

  def spec = suite("checkout")(
    test("holds up at fifty a second"):
      for
        result <- measured("checkout"):
          checkout.at(50.perSecond, over = 1.minute).expecting(p99(placeOrder) under 200.millis)
      yield assert(result)(failedNone && metEveryGoal),
  )
```

Extending it brings `TestAspect.sequential` and `TestAspect.withLiveClock`. The
second is the one to know about: zio-test hands a spec a `TestClock`, so any
time the *spec* takes (a readiness retry, a `Schedule`, a timeout) never
advances and the spec hangs rather than failing. The run itself is on the wall
clock and is fine, which is what makes it hard to find. There is no timeout in
there, because the right one is the length of what is being run: add
`@@ TestAspect.timeout(...)` to the suite yourself.

`measured` runs it, writes its page under `reportsTo` and appends its table to
the job summary, and the index over that directory is written once, after the
last test. `reportsTo` defaults to `target/proofload`; a caller who wants the
result without a report has `proofload.run`.

Durations are whichever kind you already hold: `1.minute` from `zio` and
`1.minute` from Scala's `DurationInt` both work, and nothing needs a conversion
or a language flag. A run fails with a `ProofloadError`, which is `Invalid`,
`Interrupted` or `Failed`: the question a caller is asking is whether to retry.
Nothing the target did reaches that channel, because a refused connection is a
measurement.

[from-scala.md](from-scala.md#in-a-zio-test-spec) has the full spec, which the
build compiles and runs.

## Without a test framework

A simulation is a value and `run` is an extension on it, so a `main` is enough.
This is the shape for a scheduled soak or a one-off from a shell.

```kotlin
import io.github.matthewjones372.proofload.engine.run
import io.github.matthewjones372.proofload.report.writeHtmlReport
import java.nio.file.Path

fun main() {
    val result = checkout.at(50.perSecond, over = 10.minutes).run()

    result.writeHtmlReport(Path.of("build/reports/soak.html"))
}
```

## Run on an engine of your own

`proofload-core` declares what an engine is, and `proofload-engine` provides the
only implementation, on virtual threads:

```kotlin
fun interface Engine {
    fun run(simulation: Simulation): RunResult
}
```

A JUnit test class can name another engine by implementing `RunsOn`. A class
that does not implement it runs on virtual threads. `Actors()` below stands for
an engine of your own:

```kotlin
import io.github.matthewjones372.proofload.junit5.RunsOn

class CheckoutLoadTest : RunsOn {

    override val engine = Actors()

    @LoadTest
    fun `checkout holds up at fifty a second`(proofload: Proofload) { ... }
}
```

and a Kotest spec names one by passing it:

```kotlin
val result = proofload(Actors()).run(checkout.at(50.perSecond, over = 1.minutes))
```

The engine you name is still wrapped so that one run has the machine at a time,
two tests that start together measure the target one after the other rather than
measuring each other, whichever engine sends them. Wrapping an already-exclusive
engine is safe: the lock notices the thread already holds it.

## One run at a time, on the whole machine

Two runs sharing a host measure each other. A run therefore takes the machine
while it lasts: two `@LoadTest` methods that start together run one after the
other, and a second JVM on the same host waits for the first.

```
proofload: waited 204.682774ms for the machine
proofload: checkout — 30,000 users over 10m, 2 steps each
```

A run that did not queue says nothing. The lock is a `FileLock` on a file under
`java.io.tmpdir`, so the OS releases it when a process dies, so a killed run frees
the machine for the next one with nothing to clean up.

It degrades rather than fails. A read-only temp directory, a container without
one, a filesystem that will not lock: each says so once and runs anyway, with
the guarantee back to one run at a time in this JVM. A load test that died
because it could not create a lock file is one people stop running.

Four properties, all optional:

| Property | Default | What it does |
|---|---|---|
| `proofload.exclusive` | `true` | `false` lets runs overlap. Set it on a benchmark, which is measuring the machine and must not queue behind a test. |
| `proofload.exclusive.file` | `$java.io.tmpdir/proofload-machine.lock` | Where the lock lives. One name per host is what makes two people queue for each other. |
| `proofload.exclusive.timeout` | none | Seconds to queue before giving up, failing with the holder's pid. Unset waits indefinitely. |
| `proofload.resolution` | measured | A floor somebody already measured, skipping `calibrate()`. A floor named rather than measured carries no probe, so nothing can compare two machines by it. |

Two containers on one host do not share a temp directory, so they cannot queue
for each other this way.

## See what a scenario does before running it

`trace` walks one user through the scenario and prints what each step did. Use
it when a step fails and you want to know which one, before spending ten
minutes of load finding out.

```kotlin
import io.github.matthewjones372.proofload.engine.trace

checkout.trace()
```

```
proofload: trace checkout
proofload:   /products     ok
proofload:   /orders       FAILED status 500 — user abandoned here
```

It is a diagnostic and returns nothing. One pass on a cold JVM, with no
schedule behind it, has no number worth reporting.

A scenario is an ordinary value, so you can also just read it:

```kotlin
checkout.stepNames                                  // [/products, /orders]
checkout.at(50.perSecond, over = 1.minutes).userCount()   // 3000, before anything is sent
```

## Know how long it will take

A run says its shape before it departs and counts down while it goes:

```
proofload: checkout — 30,000 users over 10m, 2 steps each
proofload: 00:05  departed 250  in flight 3  behind 88.033us  09:55 left
proofload: 10:00  departed 30,000  in flight 41  behind 96.718us  draining
```

Nothing there is estimated. The window and the user count are the profile's own
arithmetic, available before a request leaves, and `left` is the schedule's
remainder. Past the window it says `draining` rather than counting to zero:
what is left then is the target finishing the users it was given, and this end
of the wire does not know how long that takes.

A capacity search is the one thing whose length nobody can work out in advance,
because the ladder stops as soon as it has the knee and then bisects. So it
prints a bound, and narrows it:

```
proofload: capacity — at most 10 rungs of 2s and the bisection after them, so at most 30s
proofload: rung 1 — 4/s passed, at most 28s left
proofload: rung 5 — 20/s failed, at most 20s left
proofload: rung 9 — 17/s passed, at most 12s left
```

The figure is an upper bound, not a forecast. A rung that is void (the
generator lost ground, or the process ran out of file descriptors or ports)
says so and stops the search, because its answer is about this machine rather
than about the target.

## Quieten the progress lines

When the output is somebody else's report (a CI step that parses stdout, a test
framework), hand it a quiet one:

```kotlin
import io.github.matthewjones372.proofload.Progress

val proofload = Proofload(Progress.silent)

// or a different interval
val proofload = Proofload(Progress.lines(every = 30.seconds))
```

Nothing in a tick is read from what was recorded. A live histogram read for a
percentile would put the watcher's work on the path being timed, so these are
numbers the scheduler already keeps: a run costs the same watched as unwatched.

## Watch a two-hour soak from a dashboard

The progress line is one reporter. `and` puts a second one beside it, and
`otlpEvery` pushes what the scheduler knows to a collector while the run is
still going:

```kotlin
import io.github.matthewjones372.proofload.Progress
import io.github.matthewjones372.proofload.and
import io.github.matthewjones372.proofload.engine.Proofload
import io.github.matthewjones372.proofload.otel.otlpEvery
import kotlin.time.Duration.Companion.seconds

val proofload = Proofload(
    Progress.lines() and otlpEvery(30.seconds, to = "http://collector:4318/v1/metrics", run = "soak-2026-09-03"),
)
```

```kotlin
dependencies {
    testImplementation("io.github.matthewjones372:proofload-engine:0.1.0-rc4")
    testImplementation("io.github.matthewjones372:proofload-otel:0.1.0-rc4")
}
```

What is live is what the scheduler already keeps, in the names the finished
export uses:

| Series | What it says |
|---|---|
| `proofload.departed` | users handed to a thread so far |
| `proofload.in_flight` | users still running, a parked user counts here |
| `proofload.requests` | requests recorded since the last push |
| `proofload.failures` | failures recorded since the last push |
| `proofload.behind.last` | how late the last departure was |

Read `proofload.behind.last` before any of the counts. Where it is growing, the
rate you named is no longer being offered, and every number under it describes
a lighter test than the one you asked for.

**There is no live percentile.** Reading the histograms while requests are
being recorded would put a lock on the path being timed. Counts and the latest
lateness are numbers the scheduler keeps anyway, so a watched run costs what an
unwatched one does. The percentiles arrive with the finished run, through
`sendOtlp`.

Two more things worth knowing:

- **A collector that is down does not stop the run.** The first refusal is one
  line on stderr and nothing after it. Losing the dashboard is not losing the
  measurement.
- **The push happens on the sampler's thread**, so a slow collector delays the
  next sample rather than a departure. `otlpEvery` takes its own interval for
  that reason; the same applies to any reporter of your own that goes over a
  network, which `Progress.throttled(every)` wraps.

Pass the same `run` label to `sendOtlp` at the end and the live series and the
finished one join on one dashboard. The live points carry no `step`, because a
snapshot is the run rather than its steps, which is also what keeps them a
different series from the finished export's, so a run pushed live and then sent
at the end is not counted twice.
