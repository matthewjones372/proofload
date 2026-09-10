# Proofload from Scala

Scala 3 reads Kotlin bytecode, so a Scala caller is in the position
[from-java.md](from-java.md) describes and one step worse. The value-class
hashes and the erased `@JvmInline` types are there identically, and on top of
them Scala brings its own duration: a Scala codebase writes `1.minute` and
means a `scala.concurrent.duration.FiniteDuration`, while every Proofload
signature takes a `kotlin.time.Duration` that arrives as a bare `Long` with no
unit attached.

`proofload-scala` sits on `proofload-java` rather than on core. The unmangling is
written once, and a facade method missing from Java is missing from Scala in
the same commit rather than two months later.

Compiled against **Scala 3.3.8**, the LTS line. A published Scala library can
only be read by a compiler at least as new as the one that built it, so this is
deliberately not the newest release.

The version is declared once, in `proofload-scala/build.gradle.kts`, and
`TastyVersionTest` reads the header of the compiled output and fails the build
where it is newer than the line above. 0.1.0-rc3 shipped TASTy 28.9 from Scala
3.9.0 against this same paragraph, which no consumer below 3.9 could read; the
test is there so the page and the jar cannot disagree again.

## Taking it

```kotlin
// build.gradle.kts
dependencies {
    // Brings proofload-java, and proofload-core, proofload-engine and proofload-http
    // with it. No Scala suffix on the coordinate while there is one supported
    // Scala version; a suffix invented early is one to keep publishing forever.
    implementation("io.github.matthewjones372:proofload-scala:$proofloadVersion")
}
```

## A load test

```scala
import io.github.matthewjones372.proofload.java.Goals
import io.github.matthewjones372.proofload.java.Https
import io.github.matthewjones372.proofload.java.Results
import io.github.matthewjones372.proofload.java.Simulations
import io.github.matthewjones372.proofload.scala.Proofload
import io.github.matthewjones372.proofload.scala.apply
import io.github.matthewjones372.proofload.scala.exec
import io.github.matthewjones372.proofload.scala.given
import io.github.matthewjones372.proofload.scala.http
import io.github.matthewjones372.proofload.scala.pause
import io.github.matthewjones372.proofload.scala.perSecond
import io.github.matthewjones372.proofload.scala.scenario
import io.github.matthewjones372.proofload.scala.sessionKey
import io.github.matthewjones372.proofload.scala.step
import _root_.scala.concurrent.duration.DurationInt
import _root_.scala.language.implicitConversions
```

`_root_.scala` because the module's own package is named `scala`, which shadows
the root one inside it. Every extension is imported by name rather than with a
wildcard, so the line that makes `50.perSecond` compile is visible.

```scala
private val orderId = sessionKey[String]("orderId")

private val browse = step("browse")

private val placeOrder = step("place order")
```

```scala
val api = http.baseUrl("https://orders.internal")

val checkout = scenario("checkout")(
  exec(browse, api.get("/products").expecting(200)),
  exec(
    placeOrder,
    Https.capturing(
      api.post("/orders").body("""{"cart":"1 anvil"}""").expecting(201),
      orderId,
      response => response.header("location"),
    ),
  ),
  pause(1.second),
)
```

```scala
val result = Proofload().run(
  Simulations.at(
    checkout,
    50.perSecond,
    1.minute,
    Goals.p99Under(placeOrder, 200.millis),
    Goals.failureRateUnder(0.1),
  ),
)
```

`Goals.p99Under` takes a `java.time.Duration` and is handed `200.millis`,
because the conversion is `given`. That is what the `implicitConversions`
import above is for; it is Scala's rule about applying a conversion, not this
module's.

Read what it measured off the result. The percentile comes back a
`FiniteDuration`, so it compares against one:

```scala
val tail = result(placeOrder).responseTime.p99
```

`result(placeOrder)` reaches `count`, `ok`, `failed` and `ran`, and
`responseTime` or `serviceTime` reaches `p50`, `p95`, `p99` and `max`. It holds
no number and computes none. Every one is read through `Results`, on core's
own `RunResult`. It exists because `Timing`'s percentiles are
`kotlin.time.Duration` properties, so their getters carry a value-class hash
and no extension method can reach them.

`result.offered` is the other half of reading a run, and an `Option`: it is
what the run asked for beside what actually left, and it cannot be said of a
closed run or of a result nobody ran. `asked`, `left`, `over` and `share` come
off it. Reach for it when a run says it fell behind: the service times were
measured from the departures that happened, so they describe the target at
`left` rather than at `asked`, which is a smaller experiment than the one
somebody asked for and a real one.

## What the module is

`FiniteDuration` both ways, `perSecond` and `perMinute` on `Int` and `Double`,
`sessionKey[T]`, `step`, `exec`, `pause`, `scenario`, `http.baseUrl`,
`Proofload()`, `at`, the readers above, and the capacity search below. Nothing
else: everything else on `Goals`, `Https`, `Results` and `Simulations` is a Java
static and is called directly, as the snippets here do.

`sessionKey[T]` is the one call Scala does better than Java. `ClassTag`
recovers at compile time the type Java has to be handed at runtime, so the
Scala call reads like the Kotlin one rather than like the Java one.

`scenario(...)` takes a vararg rather than returning a builder. Scala has no
lambda-with-receiver problem to work around, and a builder imported from Java
would read like Java. Each entry is what it does to the Java builder, which
stays the one place a scenario's steps are frozen, so this is extension
methods over the same values, not a second way to describe a run.

## What it deliberately is not

**No effect-system integration in this module: no ZIO, no cats-effect, no
Pekko.** An effect runtime is a scheduler, and a second scheduler inside a load
generator means the tool measures its own queueing and reports it as the
target's latency. A step body may call effectful code and run it itself; what
does not happen is `proofload-scala` handing back a `ZIO` or an `IO`. A test
framework is a different question from a runtime, and is answered by
[`proofload-zio-test`](#in-a-zio-test-spec) below.

**No Scala 2.13.** Two compilers doubles the build and the cross-publishing for
a version whose Kotlin interop is worse.

**No `scala.collection` in any signature.** What comes back is what Java and
Kotlin get: `Results.verdicts` hands back a `java.util.List`.

## In a zio-test spec

A load test is an ordinary test, in whichever framework the service is already
tested in. `proofload-zio-test` is the third of those, beside `proofload-junit5`
and `proofload-kotest`.

```kotlin
// build.gradle.kts
dependencies {
    // zio-test is compileOnly here: the spec that uses this already has it.
    testImplementation("io.github.matthewjones372:proofload-zio-test:$proofloadVersion")
}
```

```scala
import io.github.matthewjones372.proofload.ziotest.proofload
import io.github.matthewjones372.proofload.ziotest.metItsGoals
import zio.ZIO
import zio.test.ZIOSpecDefault
import zio.test.assertTrue
```

```scala
object CheckoutSpec extends ZIOSpecDefault:

  def spec = suite("checkout")(
    test("holds its failure rate at 20 a second"):
      ZIO.scoped:
        for
          server <- serving
          api = http.baseUrl(s"http://localhost:${server.getAddress.getPort}")
          browsing = scenario("browsing")(exec(browse, api.get("/products").expecting(200)))
          result <- proofload.run(
            Simulations.at(browsing, 20.perSecond, 500.millis, Goals.failureRateUnder(0.1)),
          )
        yield result.metItsGoals && assertTrue(
          result(browse).count > 0,
          result(browse).failed == 0L,
        ),
  )
```

`proofload.run` is the whole module, and the argument is entirely about which
executor it runs on. It is `ZIO.attemptBlocking` around the same silent runner
the other two framework modules build: the call holds its thread for the length
of the run while the engine sends on virtual threads, and on ZIO's compute pool
that is a starved runtime, which is a scheduler this tool would then measure
and report as the target's latency.

Three things follow from that, and are worth knowing before you write one:

- **`TestClock` cannot move a run, and must not.** What a run measures is the
  wall clock. `TestClock.adjust(1.minute)` will not fast-forward a one-minute
  simulation, and a version that let it would be reporting a number nothing
  measured.
- **`TestAspect.parallel` is safe.** The runner takes the machine for the
  duration of a run, so two load tests started at once measure it one after the
  other rather than measuring each other.
- **Nothing inside the run is a `ZIO`.** A step body is an `Action` and stays
  one. No fiber sits between the departure clock and the socket, which is the
  thing this project refuses and the reason it can claim its own overhead.

`result.metItsGoals` is for when several numbers decide the test: it reads the
run's own verdicts and fails naming every goal that missed and the remedy each
carries, rather than stopping at the first assertion that did. Where one number
decides it, `assertTrue` on the result reads better.

## The rate it holds

`sustainable` is the other question this tool answers: not whether the service
held at a rate somebody picked, but the highest rate it holds at all. It climbs
a ladder of ten rungs, keeps going two rungs past the first that missed a goal,
then bisects between the last rung that passed and the first that did not.

```scala
capacity <- proofload.run(
  browsing.sustainable(upTo = 100.perSecond, holding = 500.millis, Goals.failureRateUnder(50)),
)
```

The goals are a vararg, as `scenario(...)`'s steps are. `search.warmingUp(over)`
warms every rung at that rung's own rate, and none of it is recorded.

What comes back is the curve rather than a number, and the number is read off
it: `capacity.rate` is an `Option[Rate]`, empty where even the lowest rung
missed a goal, `capacity.limitedBy` is the goal that stopped the climb, and
`capacity.voided` says the generator ran out of room before the target did, in
which case the rate is a floor this machine reached and not a ceiling the target
could not pass.

```scala
capacity.curve.forall(rung => rung.rate.getPerSecond > 0.0),
capacity.curve.forall(rung => rung.offered.getPerSecond > 0.0),
```

`rung.rate` is the rate the rung asked for and `rung.offered` is the rate the
load actually left at. They are the same number on a rung that was really
offered, and the gap between them is what makes a rung void. Both are read
through `Searches`, because `Rate` is a value class and every core call that
takes or returns one compiles to a name with a hash in it.

There is no `notWorseThan` for comparing against a baseline. `Difference
.notWorseThan` takes a `Share`, so its JVM name carries a value-class hash and
no Scala caller can name it; the facade that would fix that is a Java-facing
baselines module, which is a spec of its own.

## The gate

[`examples-scala`](../examples-scala) is a module whose whole content is the
two load tests above, one compiled by `./gradlew build` and one compiled and run
by it. There is no `.api` dump for
`proofload-scala` to move: what BCV records of a Scala module is
`Durations$package$`, lazy-init closures and qualified-private members Scala
emits as public bytecode: names no caller can type, moving on edits no caller
can see. So the compiler is the gate, and `FromScalaDocTest` fails when a line
on this page stops being a line of that source.
