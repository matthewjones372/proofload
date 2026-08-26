# 0008 — A load test is a test

## Problem

Running a simulation means calling `run()` from a `main` and reading numbers
off stdout. Teams keep their assertions somewhere else — a shell script, a
person looking at a chart — so a regression in p99 does not fail anything.

Gatling's answer is a plugin, a `Simulation` base class and a runner of its
own. The bet here is that a load test is an ordinary JUnit test: it is already
wired into CI, it already reports failures, and nobody has to learn a runner.

## Not doing

- No assertion DSL. Kotest matchers already read well against a `RunResult`.
- No report rendering. The extension does not depend on the report modules; a
  test that wants a page calls `writeHtmlReport` itself.
- No parallel execution policy, no test ordering, no fixtures.
- No annotation carrying the rate or the duration. A rate belongs in Kotlin,
  where it can be a constant shared by two tests, not in a string in an
  annotation.

## Shape

```kotlin
import io.github.matthewjones372.kestrel.junit5.Kestrel
import io.github.matthewjones372.kestrel.junit5.LoadTest
import io.github.matthewjones372.kestrel.perSecond
import io.kotest.matchers.comparables.shouldBeLessThan
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class CheckoutLoadTest {

    @LoadTest
    fun `checkout holds p99 under 200ms at 50 a second`(kestrel: Kestrel) {
        val result = kestrel.run(checkout.at(50.perSecond, over = 1.minutes))

        result["pay"].responseTime.p99 shouldBeLessThan 200.milliseconds
        result.failed shouldBe 0L
    }
}
```

- `kestrel-junit5`, depending on `kestrel-core`, `kestrel-engine` and JUnit 5.
  Its dependency test asserts those and no second stack.
- `@LoadTest` — a meta-annotation: `@Test`, plus the extension, plus a timeout
  long enough for a real run.
- `Kestrel` — injected as a parameter. It runs a `Simulation` and keeps the
  `RunResult` for the extension to use.
- On failure, the extension appends a per-step count and p99 summary to the
  failure message, so a red build says which step moved without anyone opening
  an artifact.

## Why this shape

A parameter rather than a base class. Inheritance forces every load test into
one hierarchy, and the first person who wants a load test inside an existing
integration-test class is stuck. A resolved parameter composes with whatever
the team already does.

The rate stays in Kotlin. An annotation taking `rate = 50, over = "1m"` cannot
share a constant between two tests, cannot be computed, and turns a duration
into a string somebody parses.

The extension deliberately knows nothing about reports. Depending on the
renderer would put HTML on the classpath of every load test, and the useful
half — which step moved — is a string this module can build itself.

## Stack

- [ ] **`spec-0008-module`** — module, wiring, dependency test, `Kestrel` and
      the parameter resolver.
      Done when: a `@Test` taking a `Kestrel` runs a one-user simulation and
      asserts on the result, and `./gradlew build` is green.
- [ ] **`spec-0008-annotation`** — `@LoadTest` as a meta-annotation, with its
      timeout, and the extension registered through it rather than by hand.
      Done when: a test annotated only `@LoadTest` runs and resolves `Kestrel`.
- [ ] **`spec-0008-failure`** — the failure-message summary.
      Done when: a failing assertion's message names the step that moved and
      its p99, and a passing test adds nothing.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

Answered by the architect:

1. **The extension does not fail a test on its own**, not even when the run
    fell behind schedule. It appends the fact to the failure message and lets
    the assertions decide. A tool that fails a build for a reason the test did
    not ask about is a tool people disable.
2. **One `Kestrel` per test method**, not per class. Two tests in a class are
    two runs and must not share a recorder.
3. **`Kestrel.run` is the only entry point.** No static, no global registry.
