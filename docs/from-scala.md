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

Compiled against **Scala 3.3.8**, the LTS line, and that number is a floor
rather than a note: a published Scala library carries its own TASTy, TASTy is
forward-incompatible, and so no compiler older than the one that built this
module can read it. Any Scala 3.3 or newer works. Below that, and no dependency
override helps, because the version is in the bytecode.

It is declared once, in `buildSrc/src/main/kotlin/ScalaLts.kt`, next to the
ceiling on the TASTy this module is allowed to emit. `TastyVersionTest` reads
the header of the compiled output and fails the build here when it rises above
that ceiling, and `ScalaVersionTest` fails when this page and the build stop
agreeing. Both exist because `0.1.0-rc3` was published against 3.9.0 by a
grouped dependency bump, emitted TASTy 28.9, and could not be read by anyone
below Scala 3.9 while this paragraph still promised the LTS line.

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
import io.github.matthewjones372.proofload.java.Https
import io.github.matthewjones372.proofload.java.Results
import io.github.matthewjones372.proofload.scala.Proofload
import io.github.matthewjones372.proofload.scala.apply
import io.github.matthewjones372.proofload.scala.at
import io.github.matthewjones372.proofload.scala.exec
import io.github.matthewjones372.proofload.scala.expecting
import io.github.matthewjones372.proofload.scala.failureRate
import io.github.matthewjones372.proofload.scala.http
import io.github.matthewjones372.proofload.scala.p99
import io.github.matthewjones372.proofload.scala.pause
import io.github.matthewjones372.proofload.scala.percent
import io.github.matthewjones372.proofload.scala.perSecond
import io.github.matthewjones372.proofload.scala.scenario
import io.github.matthewjones372.proofload.scala.sessionKey
import io.github.matthewjones372.proofload.scala.step
import _root_.scala.concurrent.duration.DurationInt
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
  checkout
    .at(50.perSecond, over = 1.minute)
    .expecting(p99(placeOrder) under 200.millis, failureRate under 0.1.percent),
)
```

The goals are infix, as they are in Kotlin: `p99`, `p95`, `p50`, `p999` and
`goodput` take the step and wait for the limit, `failureRate` takes the run or
one step and waits for the share, and `1.percent` is a `Share`. Each is a second
spelling of the goal `Goals` builds and equal to it, so nothing here is a second
way to describe a run. Add `of = Clock.ServiceTime` to ask a percentile of the
other clock.

`at` says what to send and `expecting` what it has to achieve, which is the
order Kotlin says them in. Nothing above needs a conversion or a language flag:
every entry point takes the `FiniteDuration` a Scala codebase already writes,
and a `java.time.Duration` beside it. The `given` conversions are still there for
a caller handing one of those to a Java static, and applying one needs
`import scala.language.implicitConversions`, which is Scala's rule and not this
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

`result.fellBehind`, `result.lostGround` and `result.ranOutOfRoom` are the three
questions to ask before believing any of it: whether the generator's own
lateness is a material part of the tail, whether it lost the schedule outright,
and whether this process ran out of its own room. `result.concurrency` is
Little's law over the segment the run settled into, as a sealed trait rather
than a nullable: `Concurrency.Measured` carries `observed`, `ratio`, `backlog`
and `agrees`, and `Concurrency.Absent(because)` says why the law could not be
asked. All four are extensions rather than `RunResultKt.fellBehind(result)`,
which is what a load test written against `0.1.0-rc1` had in its source: a file
class is how Kotlin happens to compile a file and has no business in a
consumer's imports.

`result.against(baseline)` compares two runs, and takes the clock the comparison
should be read on: `result.against(baseline, of = Clock.ServiceTime)` where the
generator fell behind, because the response times of such a run carry a wait
this tool caused. The `Comparison` it hands back is what `writeHtmlReport` and
`markdown` take, and it records which clock it was read on so the page says so
rather than assuming.

`result.offered` is the other half of reading a run, and an `Option`: it is
what the run asked for beside what actually left, and it cannot be said of a
closed run or of a result nobody ran. `asked`, `left`, `over` and `share` come
off it. Reach for it when a run says it fell behind: the service times were
measured from the departures that happened, so they describe the target at
`left` rather than at `asked`, which is a smaller experiment than the one
somebody asked for and a real one.

## Data per user

Ten thousand users sending one identical request measure whatever the target
does with a duplicate. A feeder gives each user its own data, as a function of
the user's number rather than a cursor over a source: a cursor is shared state
on the path every departure takes, and a generator that locks to decide what to
send is measuring itself. The number is also what makes a run repeatable, so
user 4,001 gets the same value tomorrow and a failure that names a value can be
looked at rather than reproduced by luck.

```scala
private val personId = sessionKey[String]("personId")

private val target = sessionKey[String]("target")
```

```scala
graph
  .at(20.perSecond, over = 500.millis)
  .fedBy(feed(personId)(user => (user % 82 + 1).toString) + feed(target)(user => (user % 61 + 7).toString)),
```

The value is picked up by the `{personId}` and `{target}` in the request:

```scala
graph = scenario("graph")(exec(pathTo, api.get("/people/{personId}/path-to/{target}").expecting(200)))
```

`feed` takes its function in a second parameter list so the lambda reads as a
block, and `+` combines two feeders because that is what the Kotlin DSL calls
it. `feedFrom(key, values)` indexes a `Seq` by the user's number and wraps round
at the end: a feeder that ran out would end a load test for a reason that has
nothing to do with the target. `fedBy` attaches one to a simulation or to a
search, which are the only two things a feeder attaches to.

## What it leaves behind

```scala
result.writeHtmlReport(reports.resolve("checkout.html"))
result.appendToStepSummary()
writePagesIndex(reports)
```

`result.markdown` is the same table as text, `result.toHtmlReport()` the page as
a string, and `capacity.writeHtmlReport(path)` draws the curve with the
operating point marked. Every optional argument is a Scala default rather than a
hand-placed `null`: Kotlin's defaults do not cross the boundary, so the rc1
version of these calls was `HtmlReportKt.writeHtmlReport(result, path, null,
null, java.util.List.of())` and the caller had to know the order. The step
summary's environment reader is a `String => Option[String]`, so nothing here
puts `kotlin.jvm.functions.Function1` in a signature a caller sees, and
`appendToStepSummary` answers `NotOnActions` off Actions rather than throwing:
the same call runs on a laptop.

Both report modules are `compileOnly` on `proofload-scala`. Calling one of these
means having the module it belongs to on your own classpath, which is the same
thing as being able to name what it returns.

## What the module is

`FiniteDuration` both ways, `perSecond` and `perMinute` on `Int` and `Double`,
`sessionKey[T]`, `step`, `exec`, `pause`, `scenario`, `http.baseUrl`,
`Proofload()`, `at`, `expecting`, `feed`, `feedFrom`, `+`, `fedBy`, the goals
above, the readers above, the outputs above, and the capacity search below.
Nothing else: `Https` and `Results` are Java statics and are called directly, as
the snippets here do.

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
import io.github.matthewjones372.proofload.ziotest.ProofloadSpec
import io.github.matthewjones372.proofload.ziotest.failedNone
import io.github.matthewjones372.proofload.ziotest.metEveryGoal
import io.github.matthewjones372.proofload.ziotest.proofload
import io.github.matthewjones372.proofload.ziotest.metItsGoals
import zio.ZIO
import zio.test.assert
import zio.test.assertTrue
```

```scala
object CheckoutSpec extends ProofloadSpec:

  override val reportsTo: Path = Path.of("build/proofload")

  def spec = suite("checkout")(
    test("holds its failure rate at 20 a second"):
      ZIO.scoped:
        for
          server <- serving
          api = http.baseUrl(s"http://localhost:${server.getAddress.getPort}")
          browsing = scenario("browsing")(exec(browse, api.get("/products").expecting(200)))
          result <- measured("checkout"):
            browsing.at(20.perSecond, over = 500.millis).expecting(failureRate under 0.1.percent)
          table <- proofload.markdown(result)
          held = assert(result)(failedNone && metEveryGoal)
        yield held && result.metItsGoals && assertTrue(
          result(browse).count > 0,
          result(browse).failed == 0L,
          table.contains("browse"),
        ),
  )
```

`ProofloadSpec` is a `ZIOSpecDefault` with the two aspects a load spec cannot
forget, and `measured` is the run, its page and its job-summary entry in one
call. What is left of the spec above is the measurement.

**`withLiveClock` is the one to know about.** zio-test hands a spec a
`TestClock`, so any time the *spec* takes (a readiness retry, a `Schedule`, a
timeout) never advances and the spec hangs rather than failing. The run itself
is on the wall clock and is fine, which is what makes it hard to find: the load
works and the scaffolding around it stops. `sequential` is the other, so two
load tests do not queue behind each other while their own readiness checks time
out.

**There is no timeout in there.** The right one is the length of what is being
run, which a base class cannot know, and a wrong default is worse than none. Add
`@@ TestAspect.timeout(...)` to the suite, written against the ladder you are
climbing: a search's own `worstCase` is the number to use.

`reportsTo` is where the page goes and defaults to `target/proofload`, relative
to the working directory the test JVM runs in, which is the module directory
under both Gradle and a forked sbt. It is per spec rather than per run because
the index is per directory, and the index is written once, after the last test:
a spec that measured nothing leaves nothing behind.

`proofload.run` underneath is the whole of the original module, and its argument
is entirely about which executor it runs on. It is `ZIO.attemptBlocking` around
the same silent runner the other two framework modules build: the call holds its
thread for the length of the run while the engine sends on virtual threads, and
on ZIO's compute pool that is a starved runtime, which is a scheduler this tool
would then measure and report as the target's latency.

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

Durations are zio's here and nothing has to be converted. `zio.Duration` *is*
`java.time.Duration`, and `at`, `pause`, `sustainable`, `warmingUp`, `under` and
`goodput` each take one beside the `FiniteDuration` they already took. So a spec
that imports `zio.*` writes `1.minute`, needs no `DurationInt`, and sets no
language flag. Importing both spellings is what makes `15.seconds` ambiguous,
and a load test on `0.1.0-rc1` resolved that by dropping `import zio.*` and
writing `FiniteDuration(15, TimeUnit.SECONDS)` by hand.

`proofload.run` fails with a `ProofloadError`: `Invalid` where the simulation
could not be run as written, `Interrupted` where the run did not finish, and
`Failed` for everything else. The question a caller is really asking is whether
to retry, and three cases answer it without matching on an exception class. It
is a `Throwable` and ZIO is covariant in its error type, so code that named
`Task[RunResult]` still compiles.

Nothing the target did reaches that channel. A refused connection and a bug in a
step body are both recorded as failed requests, with the reason each failed for,
and the run succeeds: a target that refuses every connection is a measurement,
and losing it to an exception would throw away the answer.

The outputs are effects here rather than extensions, on the same blocking
executor the run went out on: `proofload.writeHtmlReport(result, path)`,
`proofload.appendToStepSummary(result)`, `proofload.markdown(result)` and
`proofload.writePagesIndex(directory)`. `measured` above is the first two of
those with the run in front of them.

A module that owns `attemptBlocking` for the run should own it for the run's
outputs too, or a caller learns that some of this library is effectful and some
is not with no rule for telling which. `markdown` is a `UIO`: it reads what is
already in hand and writes nothing, so a throw there is a bug here rather than
something a caller can do anything about.

The goals are also `Assertion` values, which is what `metItsGoals` cannot be:
`assert(result)(failedNone && metEveryGoal)` is what a caller writes.
`p50Under`, `p95Under`, `p99Under`, `failureRateUnder`, `failedNone`,
`keptSchedule` and `metEveryGoal`, plus `meeting(goal)` for any goal at all.
Each is core's own goal, judged: nothing there recomputes a number or invents a
description, so an assertion cannot disagree with the report about the same run.
They negate with `!`, join with `&&`, and zio-test names the half that failed.

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
  browsing.sustainable(upTo = 100.perSecond, holding = 500.millis, failureRate under 50.percent),
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
