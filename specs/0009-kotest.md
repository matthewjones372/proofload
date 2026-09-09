# 0009 — Kotest

## Problem

Spec 0008 makes a load test a JUnit test. Teams on Kotest have to drop to a
JUnit class to write one, which is exactly the "adopt our runner" tax this
project exists to avoid — and it quietly proves the core is not as
framework-agnostic as the README claims.

## Not doing

- No new engine behaviour. This module wires an existing runner into a second
  framework and nothing else.
- No Kotest-specific assertion helpers. `RunResult` reads well with the
  matchers Kotest already ships.
- No property-based load testing. Interesting, and a different spec.

## Shape

```kotlin
import io.github.matthewjones372.proofload.kotest.proofload
import io.github.matthewjones372.proofload.perSecond
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeLessThan
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class CheckoutSpec : StringSpec({

    "checkout holds p99 under 200ms at 50 a second" {
        val result = proofload().run(checkout.at(50.perSecond, over = 1.minutes))

        result["pay"].responseTime.p99 shouldBeLessThan 200.milliseconds
    }
})
```

- `proofload-kotest`, depending on `proofload-core`, `proofload-engine` and Kotest.
  Its dependency test asserts those and no second stack — in particular, no
  JUnit-5 module of ours on its classpath.
- The same `Proofload` runner type as 0008, moved to core or duplicated —
  whichever keeps `proofload-junit5` and `proofload-kotest` independent.

## Why this shape

The point of this module is the proof, not the convenience. If wiring a second
framework needs anything from the first, the claim that core is
framework-agnostic was decoration. The dependency test is where that claim gets
checked, so it matters more here than the API surface does.

## Stack

- [ ] **`spec-0009-module`** — module, wiring, dependency test, and `proofload()`
      inside a Kotest spec.
      Done when: a `StringSpec` runs a one-user simulation and asserts on the
      result, with no `proofload-junit5` on the classpath, and `./gradlew build`
      is green.
- [ ] **`spec-0009-listener`** — a Kotest listener carrying the same
      failure-message summary as the JUnit extension.
      Done when: a failing spec names the step that moved and its p99.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

Answered by the architect:

1. **If `Proofload` has to be shared between the two extension modules, it moves
    into `proofload-engine`**, which both already depend on. It does not move
    into `proofload-core`: core describes, it does not run.
2. **Kotest is a real third-party dependency and that is fine.** This is a leaf
    module; AGENTS.md allows exactly this, and the dependency test states it.
