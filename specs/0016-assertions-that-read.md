# 0016 — Assertions that read

## Problem

Two shapes keep turning up in tests and neither says what it means:

```kotlin
result[pay].failures shouldBe mapOf("status 503" to 10L)
result.steps.containsKey("confirm") shouldBe false
```

The first builds a map to state one number, and writes the reason as a literal
that `proofload-http` also writes somewhere else — two copies of a string that
have to agree. The second reaches past the accessor into the map behind it,
looks the step up by name after spec 0012 gave it a handle, and states a fact
backwards: what is meant is that the step never ran.

## Not doing

- No matcher library, and no dependency on one. `RunResult` is a value and
  Kotest's own matchers read fine against one; what is missing is the accessor,
  not a DSL.
- No `shouldHaveFailed`-style extensions in core. Core does not know what test
  framework is running.
- No change to what is recorded or reported.
- No removal of `failures` or `steps`. A report iterates both.

## Shape

```kotlin
import io.github.matthewjones372.proofload.http.status

result[pay].failedWith(status(503)) shouldBe 10L
result.ran(confirm) shouldBe false
```

- `StepStats.failedWith(reason): Long` — how many failed for that reason, zero
  when none did.
- `RunResult.ran(step): Boolean` — whether anything was recorded under it,
  taking a handle or a name.
- `status(code)` in `proofload-http` — the reason string the module records,
  written once and read from tests rather than copied into them.

## Why this shape

An accessor per question rather than a matcher per question. `failedWith`
answers with a number, so it composes with whatever comparison the test wants
and needs nothing from the test framework; a matcher would have to exist twice,
once for JUnit and once for Kotest.

`status(503)` matters more than it looks. The reason string is a contract
between the module that writes it and the test that reads it, and a literal on
both sides is a contract nobody checks. One function, and a change to the
format breaks compilation rather than a run.

`ran` is the honest name for the question `containsKey` was being asked. A step
that never ran is different from a step that ran and failed, and a test that
says so reads as the claim it is making.

## Stack

- [ ] **`spec-0016-accessors`** — `failedWith`, `ran`, and `status` in the HTTP
      module.
      Done when: the two lines above replace the two in the problem, and a step
      that ran but never failed answers zero rather than throwing.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

Answered by the architect:

1. **`failedWith` on a step that never ran is a lookup on `RunResult` that
    still throws.** The step has to be reachable before its failures are; only
    the reason is allowed to be absent.
2. **`ran` returns false rather than throwing**, which is the whole point of
    having it beside `get`.
3. **`status` lives in `proofload-http`**, not core. Core has no notion of a
    status code and should not grow one to make a test read better.
