# 0002 — A DSL worth using

## Problem

Spec 0001 got the model right and the ergonomics wrong. Every step names its
session and returns a `StepResult` by hand:

```kotlin
exec("browse") { session -> session.set("cart", "empty").ok() }
```

Two of those tokens are ceremony. Gatling never asks a caller to name the
session, and `.ok()` is a compile error to satisfy rather than something the
caller wanted to say. Session values come back as `Any?`, so every read is a
cast — no worse than Gatling's `.as[String]`, which is not the bar. Nothing is
published yet, so this costs two days now and a major version after 1.0.

## Not doing

- No engine, no `run()`. `.at(...)` yields a `Simulation` value and stops;
  executing it is spec 0003.
- No HTTP, no `get`/`post` steps — spec 0004, which is where a step name gets
  to default to a path template.
- No `pause`, `loop`, `doIf` or feeders. All need an engine first.
- No rewrite of `InjectionProfile`'s maths. Only its front door changes.

## Shape

```kotlin
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.sessionKey
import kotlin.time.Duration.Companion.minutes

val cart = sessionKey<String>("cart")
val orderId = sessionKey<Long>("orderId")

val checkout = scenario("checkout") {
    exec("browse") {
        set(cart, "empty")
    }
    exec("add to cart") {
        set(cart, "1 anvil")
    }
    exec("pay") {
        val id: Long? = get(orderId)      // typed, no cast
        if (id == null) fail("no order id")
    }
}

val simulation = checkout.at(50.perSecond, over = 1.minutes)
```

- `SessionKey<T>` — a name and a type, declared once and shared across files.
- `StepScope` — the receiver a step body runs against: `get`, `set`, `fail`.
  A mutable accumulator frozen into a `StepResult` when the step returns, which
  is the one place AGENTS.md allows one.
- `Rate` — `50.perSecond`, so a profile reads as a sentence.
- `Simulation` — scenario plus profile, from `Scenario.at(rate, over)`.

## Why this shape

A receiver hides the session the way a builder hides its list: still an
immutable value, still frozen before it escapes, but the caller writes only
their half. The alternative is 0001's explicit `session ->`, more honest on the
page and worse in every file with ten steps; rejected because a load test
nobody enjoys writing is a load test nobody writes.

Typed keys are where this beats the reference point rather than matching it.
Gatling hands back `Any` and asks for a cast at each use; a key carrying its
type is checked once, at declaration, and a scenario split across three files
shares state the compiler can see. `Scenario.at(...)` then collapses Gatling's
`setUp` / `inject` / `protocols` into one call, with `Simulation` still a value
underneath — spelled shorter, not hidden.

## Stack

- [ ] **`spec-0002-session-keys`** — `SessionKey<T>`, typed `Session` get/set,
      string-and-`Any` access removed.
      Done when: reading a key of the wrong type does not compile, and the
      `.api` diff shows the untyped accessors gone.
- [ ] **`spec-0002-step-scope`** — `StepScope`, `exec` taking a receiver block,
      `fail(reason)`; `Action` keeps its value-returning shape underneath.
      Done when: the Shape example above compiles with no `session ->` and no
      `.ok()` anywhere, and a failed step still carries its session on.
- [ ] **`spec-0002-setup`** — `Rate`, `perSecond`, `Simulation`,
      `Scenario.at(rate, over)`, and the README rewritten around the new shape.
      Done when: `checkout.at(50.perSecond, over = 1.minutes)` builds a
      `Simulation` equal to the one built by hand.

This supersedes the third stack entry of spec 0001, which is dropped: the
`Simulation` it described lands here instead. Engine, HTTP, reporting and the
extension modules all shift one number later.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. Does `fail(reason)` stop the step, or does the body carry on? Recommend it
    returns normally and marks the result — a step that throws to signal a
    declared failure reintroduces the second error model AGENTS.md forbids.
2. Should `get` on a missing key return null or throw? Recommend null: a
    feeder-fed key being absent is a data problem the scenario should be able
    to handle, not a crash.
3. Is `Rate` a value type or a `Double` alias? Recommend a value class, so
    `50.perSecond` and `50.perMinute` cannot be mixed up silently.
