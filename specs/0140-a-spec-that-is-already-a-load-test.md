# 0140 — A spec that is already a load test

## Problem

0108 made a run callable from zio-test. Everything around the run is still the
caller's to assemble, and it is the same assembly every time. A real load spec
written against `0.1.0-rc1` is 78 lines, of which roughly 50 are ceremony that
has nothing to do with the API being measured:

```scala
object LoadSpec extends ZIOSpecDefault:
  private val reports = Path.of("load-test/target/reports")     // a convention invented here

  def spec = suite("load")(...)
    @@ TestAspect.sequential                                     // or two runs measure each other
    @@ TestAspect.withLiveClock                                  // or the spec's own retries hang
    @@ TestAspect.timeout(40.minutes)

  private def written(label: String, runs: List[(Int, RunResult)]) =
    ZIO.attemptBlocking {                                        // the module's own outputs, wrapped by hand
      runs.foreach: (rate, result) =>
        HtmlReportKt.writeHtmlReport(result, reports.resolve(s"$label-$rate.html"), null, null, java.util.List.of())
        StepSummaryKt.appendToStepSummary(result, null, null, environment)
      PagesKt.writePagesIndex(reports)
    }
```

`withLiveClock` is the sharp one. zio-test gives a spec `TestClock` by default,
so any time the *spec* takes — a readiness retry, a `Schedule`, a timeout —
never advances and the spec hangs rather than failing. The run itself is on the
wall clock (0108) and is fine, which is what makes the omission hard to find: the
load works, the scaffolding around it stops.

Nothing about the three aspects, the report trio or the reports directory is
specific to any target. Every user will write them, most will get `withLiveClock`
wrong once, and each one invents a different directory layout.

## Not doing

- **No `ZLayer`, no `Proofload` service.** 0108's refusal stands. This is a base
  class a spec extends, not a resource it acquires.
- **No replacement for `ZIOSpecDefault`.** A project's ordinary specs are
  untouched; this is for the ones that generate load.
- **No target lifecycle.** Starting the thing under test is the caller's, and
  differs per project. `Scope` already handles it.
- No opinion about assertions. 0138 covers those.

## Shape

```scala
abstract class ProofloadSpec extends ZIOSpecDefault:

  override def aspects = Chunk(TestAspect.sequential, TestAspect.withLiveClock)

  /** Where reports and the index are written. */
  def reportsTo: Path = Path.of("target/proofload")

  /** Run it, write its report, append its table to the job summary. */
  def measured(name: String)(simulation: Simulation): IO[ProofloadError, RunResult]
```

Which leaves a spec that is only the measurement:

```scala
object LoadSpec extends ProofloadSpec:

  def spec = suite("people")(
    test("holds its failure rate at 8,000 a second"):
      for
        port   <- serving
        result <- measured("people-8000"):
                    lookups(port).at(8000.perSecond, over = 20.seconds, Goals.failureRateUnder(0.1))
      yield result.metItsGoals,
  )
```

`writePagesIndex` runs once per spec rather than once per call, on the way out.

## Why this shape

Overriding `aspects` is the hook zio-test already provides for exactly this, and
it makes the two aspects that matter unforgettable rather than documented. The
alternative — ship a `TestAspect.load` a user applies themselves — is recommended
against: forgetting it is the failure mode, so an aspect you must remember does
not fix it.

`measured` runs and reports in one call because a run whose report is not written
is a run nobody can read, and separating them means every caller writes the same
`flatMap`. A caller who wants the result without a report still has
`proofload.run`.

The base class owns `reportsTo` as a default rather than a required override, so
the zero-configuration case works and the opinionated case is one line.

## Stack

- [x] **`spec-0140-spec`** — `ProofloadSpec`, the `aspects` override, and
      `reportsTo`.
      Done when: a spec extending it inherits both aspects, and a spec whose
      body retries on a `Schedule` does not hang.
- [x] **`spec-0140-measured`** — `measured`, over 0138's output effects.
      Done when: a two-line test writes an HTML report and a job summary entry.
- [x] **`spec-0140-index`** — `writePagesIndex` once per spec, after the last
      test.
      Done when: a spec with four runs leaves one index linking four reports.
- [ ] **`spec-0140-docs`** — the zio-test section of `docs/from-scala.md`
      rewritten around it, quoted from `examples-scala`.
      Done when: the page's load spec is a compiled, run source.

## Acceptance

```bash
./gradlew build
./gradlew :proofload-zio-test:test :examples-scala:test
```

## Open questions

- **Should `timeout` be in `aspects` too?** Recommend not: the right timeout is
  the ladder's length, which the base class cannot know, and a wrong default is
  worse than none. Name it in the docs instead.
- **Does `measured` belong on the base class or as an extension usable from a
  plain `ZIOSpecDefault`?** Recommend both — the method delegates to an extension
  — so a project that cannot change its base class still gets the reporting.
- **Is `reportsTo` per spec or per run?** Recommend per spec: the index is per
  directory, and a per-run directory would leave an index of one.
- **Should this depend on 0138 landing first?** Recommend yes for
  `spec-0140-measured`, which wants the output effects rather than
  `attemptBlocking` inside the base class; `spec-0140-spec` is independent.
