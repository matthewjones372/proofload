# 0137 — A Scala surface that reads like Scala

## Problem

`proofload-scala` covers building a scenario and reading a percentile. Anything
else, a Scala caller reaches through the Kotlin file class. This is real code
from a load test on `0.1.0-rc1`:

```scala
HtmlReportKt.writeHtmlReport(result, path, null, null, java.util.List.of())
StepSummaryKt.appendToStepSummary(result, null, null, fromEnvironment)
RunResultKt.fellBehind(result)
ConcurrencyKt.getConcurrency(result)
```

Three separate leaks compound there. **File classes** (`RunResultKt`) are an
implementation detail of how Kotlin compiles a file, and they are in a
consumer's source. **Default arguments do not cross**, so every optional
parameter is a hand-written `null` and the caller must know the order. And
**`kotlin.jvm.functions.Function1` is in a public signature** —
`appendToStepSummary` takes one — so a Scala file imports `kotlin.jvm` to call a
reporting function.

Two smaller things in the same family. `Goals.p99Under(step, 200.millis)` is a
Java static where Kotlin reads `p99(placeOrder) under 200.milliseconds`, so
Scala — which does infix extensions better than either — has the worst of the
three DSLs. And the module's package is named `scala`, which shadows the root
one and is why `docs/from-scala.md` tells every reader to write
`_root_.scala.concurrent.duration.DurationInt`.

## Not doing

- **No second DSL.** Extensions over the same values, per 0095. Nothing here
  builds a scenario a different way.
- **No `Results` rewrite.** It is the model the rest of this follows.
- **No change to what the reports produce.** This is the front door only.
- **No new package for one release.** If the rename happens it happens once,
  before 1.0, with the other breaking changes.

## Shape

```scala
result.fellBehind          // RunResultKt.fellBehind(result)
result.lostGround
result.concurrency         // a Scala ADT, not Concurrency$Measured's getters
result.offered             // Option[Offered], per 0135
result.writeHtmlReport(path)
result.markdown
result.appendToStepSummary()
```

Goals as infix, matching Kotlin:

```scala
val goals = Seq(p99(placeOrder) under 200.millis, failureRate under 1.percent)
```

And one import that carries the givens, so a consumer needs neither a
`given` import nor `-language:implicitConversions`:

```scala
import io.github.matthewjones372.proofload.dsl.*
```

## Why this shape

Thin extensions holding no state and computing nothing, exactly as `Measured`
does — the numbers stay core's. The alternative — publish the Kotlin API with
`@JvmName` annotations that make the file classes prettier — is recommended
against: it changes core to suit one consumer, and the file class is still in
the caller's source.

Removing the need for `implicitConversions` means overloading on
`FiniteDuration` rather than converting to it. That is more methods and it is
worth it: a library should not make a consumer set a compiler flag.

The package rename to `dsl` is the one breaking change here and is recommended:
`scala` inside a Scala library is a name collision that every consumer pays for
in every file, forever, and rc is when it is cheapest to fix.

## Stack

- [x] **`spec-0137-results`** — `fellBehind`, `lostGround`, `concurrency` and
      `offered` as extensions, with `Concurrency` as a Scala ADT.
      Done when: no `…Kt.` appears in `examples-scala`.
- [x] **`spec-0137-reports`** — `writeHtmlReport`, `markdown` and
      `appendToStepSummary` as extensions with Scala defaults, and no
      `kotlin.jvm.functions` in any signature a caller sees.
      Done when: a spec writes all three outputs in three lines.
- [x] **`spec-0137-goals`** — `p99`, `p95`, `failureRate`, `goodput` and `under`
      as infix extensions over `Goals`.
      Done when: the Shape's `goals` compiles and equals what `Goals` builds.
- [ ] **`spec-0137-dsl`** — the package rename to `dsl`, one wildcard import
      carrying the givens, and `FiniteDuration` overloads replacing the
      conversions.
      Done when: `examples-scala` imports one wildcard, sets no language flag,
      and never writes `_root_.scala`.

## Acceptance

```bash
./gradlew build
./gradlew :examples-scala:test
```

## Open questions

- **Rename the package, or keep `scala` and live with `_root_`?** Recommend the
  rename, for the reason under Why. It invalidates every published snippet, so
  it should land with 0138 rather than alone.
- **Should `concurrency` return a Scala `enum`, or expose the Kotlin sealed
  interface with cleaner accessors?** Recommend the Scala ADT: pattern matching
  on `Concurrency.Absent(because)` is the point, and the Kotlin type is
  reachable for anyone who wants it.
- **Is `markdown` on `RunResult` the right home, given it lives in a different
  module from `RunResult`?** Recommend yes, as an extension in the report
  module's Scala package — the same shape `writeHtmlReport` already has in
  Kotlin.
