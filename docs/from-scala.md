# Kestrel from Scala

Scala 3 reads Kotlin bytecode, so a Scala caller is in the position
[from-java.md](from-java.md) describes and one step worse. The value-class
hashes and the erased `@JvmInline` types are there identically, and on top of
them Scala brings its own duration: a Scala codebase writes `1.minute` and
means a `scala.concurrent.duration.FiniteDuration`, while every Kestrel
signature takes a `kotlin.time.Duration` that arrives as a bare `Long` with no
unit attached.

`kestrel-scala` sits on `kestrel-java` rather than on core. The unmangling is
written once, and a facade method missing from Java is missing from Scala in
the same commit rather than two months later.

Compiled against **Scala 3.3.8**, the LTS line. A published Scala library can
only be read by a compiler at least as new as the one that built it, so this is
deliberately not the newest release.

## Taking it

```kotlin
// build.gradle.kts
dependencies {
    // Brings kestrel-java, and kestrel-core, kestrel-engine and kestrel-http
    // with it. No Scala suffix on the coordinate while there is one supported
    // Scala version; a suffix invented early is one to keep publishing forever.
    implementation("io.github.matthewjones372:kestrel-scala:$kestrelVersion")
}
```

## A load test

```scala
import io.github.matthewjones372.kestrel.java.Goals
import io.github.matthewjones372.kestrel.java.Https
import io.github.matthewjones372.kestrel.java.Results
import io.github.matthewjones372.kestrel.java.Simulations
import io.github.matthewjones372.kestrel.scala.Kestrel
import io.github.matthewjones372.kestrel.scala.apply
import io.github.matthewjones372.kestrel.scala.exec
import io.github.matthewjones372.kestrel.scala.given
import io.github.matthewjones372.kestrel.scala.http
import io.github.matthewjones372.kestrel.scala.pause
import io.github.matthewjones372.kestrel.scala.perSecond
import io.github.matthewjones372.kestrel.scala.scenario
import io.github.matthewjones372.kestrel.scala.sessionKey
import io.github.matthewjones372.kestrel.scala.step
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
val result = Kestrel().run(
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
no number and computes none — every one is read through `Results`, on core's
own `RunResult`. It exists because `Timing`'s percentiles are
`kotlin.time.Duration` properties, so their getters carry a value-class hash
and no extension method can reach them.

## What the module is

`FiniteDuration` both ways, `perSecond` and `perMinute` on `Int` and `Double`,
`sessionKey[T]`, `step`, `exec`, `pause`, `scenario`, `http.baseUrl`,
`Kestrel()`, `at`, and the reader above. Nothing else: everything else on
`Goals`, `Https`, `Results` and `Simulations` is a Java static and is called
directly, as the snippets here do.

`sessionKey[T]` is the one call Scala does better than Java. `ClassTag`
recovers at compile time the type Java has to be handed at runtime, so the
Scala call reads like the Kotlin one rather than like the Java one.

`scenario(...)` takes a vararg rather than returning a builder. Scala has no
lambda-with-receiver problem to work around, and a builder imported from Java
would read like Java. Each entry is what it does to the Java builder, which
stays the one place a scenario's steps are frozen — so this is extension
methods over the same values, not a second way to describe a run.

## What it deliberately is not

**No effect-system integration in this module — no ZIO, no cats-effect, no
Pekko.** An effect runtime is a scheduler, and a second scheduler inside a load
generator means the tool measures its own queueing and reports it as the
target's latency. A step body may call effectful code and run it itself; what
does not happen is `kestrel-scala` handing back a `ZIO` or an `IO`. A test
framework is a different question from a runtime, and is answered separately.

**No Scala 2.13.** Two compilers doubles the build and the cross-publishing for
a version whose Kotlin interop is worse.

**No `scala.collection` in any signature.** What comes back is what Java and
Kotlin get: `Results.verdicts` hands back a `java.util.List`.

## The gate

[`examples-scala`](../examples-scala) is a module whose whole content is the
load test above, compiled by `./gradlew build`. There is no `.api` dump for
`kestrel-scala` to move: what BCV records of a Scala module is
`Durations$package$`, lazy-init closures and qualified-private members Scala
emits as public bytecode — names no caller can type, moving on edits no caller
can see. So the compiler is the gate, and `FromScalaDocTest` fails when a line
on this page stops being a line of that source.
