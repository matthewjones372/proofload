# 0095 — Kestrel from Scala

## Problem

Scala 3 reads Kotlin bytecode, so a Scala caller is in the same position
[0094](0094-kestrel-from-java.md) describes and one step worse. The 245
hash-named functions and the erased value classes are there identically. On top
of them, Scala brings its own duration type: `scala.concurrent.duration
.FiniteDuration` is what a Scala codebase writes `1.minute` in, and Kestrel's
signatures take a `kotlin.time.Duration`, which arrives as a bare `Long` with no
unit attached.

The audience is narrower than Java's and real: a Scala service is usually
tested by the team that owns it, and the incumbent load tool in that world is
Scala-native, so "you can call it, awkwardly" is not an argument that moves
anybody.

## Not doing

- **No effect-system integration — no ZIO, no cats-effect, no Pekko.** This is
  [0010](0010-pelican.md)'s refusal again and the reason is stronger here: an
  effect runtime is a scheduler, and putting a second scheduler inside a load
  generator means this tool measures its own queueing and reports it as the
  target's latency. A step body may of course *call* effectful code and run it
  itself; what does not happen is Kestrel handing back a `ZIO` or an `IO`.
- **No Scala 2.13.** Two compilers doubles the build and the cross-publishing
  for a version whose Kotlin interop is worse.
- **No Scala DSL of its own.** Extension methods and conversions over the same
  values, not a second way to describe a scenario.
- **No `scala.collection` in any signature.** What comes back is what Java and
  Kotlin get.

## Shape

`kestrel-scala`, a thin layer over `kestrel-java`'s facade:

```scala
import io.github.matthewjones372.kestrel.scala.given
import io.github.matthewjones372.kestrel.scala.*
import scala.concurrent.duration.*

val orderId    = sessionKey[String]("orderId")
val placeOrder = step("place order")
val api        = http.baseUrl("https://orders.internal")

val checkout = scenario("checkout")(
  exec(step("browse"), api.get("/products")),
  exec(placeOrder, api.post("/orders").body("""{"cart":"1 anvil"}""").expecting(201)),
)

val result = Kestrel().run(checkout.at(50.perSecond, over = 1.minute))

assert(result(placeOrder).responseTime.p99 < 200.millis)
```

What the module is: `FiniteDuration` given both ways, `perSecond` and
`perMinute` extensions on `Int` and `Double`, `sessionKey[T]` recovering the
type parameter Java had to pass as a `Class`, and comparison operators on the
durations a result returns. Nothing else.

## Why this shape

Sitting on `kestrel-java` rather than on core is the choice worth arguing. The
unmangling is written once, and a facade method missing from Java is missing
from Scala in the same commit rather than two months later. The alternative —
Scala straight onto core, using Scala's own tolerance for odd names — needs no
Java module, and is recommended against: it would mean two independent
translations of the same 245 functions, drifting apart on exactly the calls
that are hard.

`sessionKey[T]` is the one place Scala does better than Java and should:
`ClassTag` recovers at compile time what Java has to be handed at runtime, so
the Scala call reads like the Kotlin one rather than like the Java one.

## Stack

- [ ] **`spec-0095-module`** — `kestrel-scala`, Scala 3, its dependency test,
      and the `FiniteDuration` conversions both ways.
      Done when: `1.minute` reaches a Kotlin signature and a `p99` comes back
      comparable to `200.millis`.
- [ ] **`spec-0095-dsl`** — `perSecond`, `sessionKey[T]`, `step`, `scenario`,
      `exec` over the Java facade.
      Done when: the scenario the Scala layer builds equals the one the Kotlin
      DSL builds for the same steps.
- [ ] **`spec-0095-gate`** — a compiled Scala sample module, and
      `kestrel-scala` added to `smoke`.
      Done when: removing a conversion fails the build in the sample rather
      than in a consumer's project.
- [ ] **`spec-0095-docs`** — `docs/from-scala.md`, snippets taken from the
      compiled sample.
      Done when: the page names the Scala version it was compiled against.

## Acceptance

```bash
./gradlew build
./gradlew :examples-scala:compileScala
```

## Open questions

> Answered 2026-09-04, each on the recommendation in its own bullet. The
> reasoning is left standing rather than deleted: a decision is easier to
> reopen when the alternative it beat is still written down.

- **Is this worth building at all before somebody asks?** Recommend **no**: land
  0094, publish it, and hold this spec until a Scala user turns up. Java is the
  larger audience, the Scala layer is cheap *once Java exists*, and a
  cross-compiled module nobody imports is a release-train cost paid every
  version. This spec exists so the shape is decided, not so it is built next.
- **Does the build cross-publish `_3` artifacts?** Recommend the plain
  `kestrel-scala` coordinate with no Scala suffix while there is one supported
  Scala version, and a suffix the day there are two. A suffix invented early is
  a coordinate to keep publishing forever.
- **`scenario(...)` taking a vararg of steps, or a builder like Java's?**
  Recommend the vararg: Scala has no lambda-with-receiver problem to work
  around, and a builder imported from Java would read like Java.
