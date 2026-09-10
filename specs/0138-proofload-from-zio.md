# 0138 — Proofload from ZIO

## Problem

0108 gave a ZIO service one call: `proofload.run`, on the blocking executor,
returning a `Task[RunResult]`. That was the right first move and it stops short
of the framework a ZIO user already has.

**Durations collide.** `zio.Duration` *is* `java.time.Duration`, so a ZIO caller
already holds the type `Simulations.at` wants — but `proofload-scala`'s `at`
takes `FiniteDuration`, and importing `zio.*` beside Scala's `DurationInt` makes
`15.seconds` ambiguous. A real load test resolved this by dropping `import zio.*`
and writing `FiniteDuration(15, TimeUnit.SECONDS)` by hand. The Scala module is
*less* convenient for a ZIO user than the Java facade underneath it.

**The error channel is `Throwable`.** A run that fell behind, a target that
refused the connection and a bug in a step body arrive as the same type, so a
caller who wants to retry one and fail the others has to match on exception
classes.

**Everything except the run is the caller's problem to make effectful.** `run`
wraps the engine in `attemptBlocking`, which is the hard part and is right. But
writing a report, appending to a job summary and reading a baseline are blocking
file I/O with no ZIO form, so a caller hand-rolls the wrapper the module already
knows how to write:

```scala
ZIO.attemptBlocking {                                  // written by a caller
  Files.createDirectories(into)
  HtmlReportKt.writeHtmlReport(result, path, null, null, java.util.List.of())
  StepSummaryKt.appendToStepSummary(result, null, null, fromEnvironment)
}
```

A module that owns `attemptBlocking` for the run should own it for the run's
outputs too, or the caller learns that some of this library is effectful and
some is not, with no rule for telling which.

**The assertions do not compose.** `metItsGoals` returns a `TestResult`, which
is correct and is also a dead end: it cannot be negated, combined with `&&`, or
reported through zio-test's own diffing. A ZIO user expects
`assert(result)(...)` with `Assertion` values.

## Not doing

- **No `ZLayer`, still.** 0108 argued a runner is a value with no lifetime, and
  that is right. Nothing here makes it look like a resource.
- **No `ZIO` inside a run.** A step body is an `Action`. The stream below is a
  read side; no fiber goes between the departure clock and the socket.
- **No `TestClock`.** A run measures the wall clock (0108) and always will.
- **No cats-effect.** Same shape, different library, and nobody has asked.

## Shape

Durations, by overload rather than conversion:

```scala
browsing.at(50.perSecond, over = 1.minute)      // zio.Duration, no import, no flag
```

A typed error channel:

```scala
enum ProofloadError:
  case Refused(cause: Throwable)      // the target would not answer
  case Interrupted(cause: Throwable)  // the run did not finish
  case Failed(cause: Throwable)       // anything else

proofload.run(simulation): IO[ProofloadError, RunResult]
```

Assertions that compose:

```scala
assert(result)(p99Under(pay, 200.millis) && failedNone && keptSchedule)
```

The outputs as effects, on the blocking executor the module already chose:

```scala
proofload.writeHtmlReport(result, path): Task[Path]
proofload.appendToStepSummary(result): Task[StepSummary]
proofload.markdown(result): UIO[String]
```

And a read-only progress stream, for a run somebody is watching:

```scala
proofload.watch(simulation): ZStream[Any, ProofloadError, Sample]
```

## Why this shape

Overloading on `java.time.Duration` rather than asking ZIO users to convert is
the whole of the duration fix, and it costs one method per entry point. The
alternative — a `zio.Duration` given in `proofload-zio-test` — is recommended
against: a given that silently bridges two duration types is exactly the
ambiguity that caused the problem.

`ProofloadError` as three cases rather than one per failure mode: the caller's
real question is "can I retry this", and three answers that. Adding cases later
is source-compatible for a match with a default; splitting one later is not.

`watch` as a `ZStream` is the one genuinely ZIO-native addition here. It is also
the one most likely to be wrong, which is why it is last in the stack and has an
open question against it.

## Stack

- [ ] **`spec-0138-durations`** — `java.time.Duration` overloads on `at`,
      `pause`, and the goal builders.
      Done when: a spec importing `zio.*` writes `1.minute` and compiles with no
      language flag.
- [ ] **`spec-0138-errors`** — `ProofloadError`, and `run` returning `IO`.
      Done when: a refused connection and a step-body bug are distinguishable
      without matching on an exception class.
- [ ] **`spec-0138-outputs`** — `writeHtmlReport`, `appendToStepSummary` and
      `markdown` as effects, so no caller writes `attemptBlocking` around a
      report.
      Done when: a spec writes all three outputs with no `ZIO.attemptBlocking`
      in its own source.
- [ ] **`spec-0138-assertions`** — `Assertion[RunResult]` values for the goals,
      beside `metItsGoals` rather than replacing it.
      Done when: the Shape's `assert` compiles, and its negation reports which
      half held.
- [ ] **`spec-0138-watch`** — `watch` as a `ZStream` of per-second samples.
      Done when: a two-minute run renders a live table, and a dependency test
      says no fiber sits on the departure path.

## Acceptance

```bash
./gradlew build
./gradlew :proofload-zio-test:test :examples-scala:test
```

## Open questions

- **Does `watch` belong in this spec at all?** Recommend splitting it out if the
  first three land cleanly: it is additive, it is the only entry that touches the
  engine's recording side, and 0057 already argued about progress once.
- **Should `run` keep a `Task` overload?** Recommend yes for one release, so
  0108's published signature does not break inside an rc line.
- **Do the output effects belong in `proofload-zio-test`, which would make it
  depend on both report modules?** Recommend `compileOnly` on the reports, as
  zio-test itself already is there: a caller who writes no report should not
  inherit two modules for the privilege.
- **Is `keptSchedule` an assertion or a precondition?** Recommend an assertion
  for now, and note that 0121 may make it a validity gate instead, in which case
  this one should be deleted rather than kept beside it.
