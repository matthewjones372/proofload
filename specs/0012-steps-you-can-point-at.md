# 0012 — Steps you can point at

## Problem

A step is named with a string and read back with the same string:

```kotlin
exec("place order") { ... }
result["place order"].responseTime.p99
```

Nothing checks that those two agree. Rename the step and the assertion compiles
and fails at runtime — or worse, an assertion against a step that no longer
exists throws where a reader expects a comparison. Spec 0002 removed exactly
this from session state, where `sessionKey<T>("cart")` made the compiler carry
the name, and left it in place for steps.

## Not doing

- No change to how a step is recorded or reported. A report still prints the
  name; this is about who holds it.
- No code generation, no annotation processing.
- No removal of string lookup. A report iterates names it was handed at
  runtime, and a scenario assembled from configuration has no handle to use.
- No renaming of `StepStats`, `RunResult` or anything a report already reads.

## Shape

```kotlin
import io.github.matthewjones372.kestrel.step

val browse = step("browse")
val placeOrder = step("place order")

val checkout = scenario("checkout") {
    exec(browse) { api.get("/products").send(this) }
    exec(placeOrder) { api.post("/orders").expecting(201).send(this) }
}

result[placeOrder].responseTime.p99 shouldBeLessThan 200.milliseconds
```

- `StepName` — a value holding the name, declared once and shared by the
  scenario that defines the step and every assertion about it.
- `exec(name: StepName, block: StepScope.() -> Unit)` beside the existing
  string overload.
- `RunResult.get(step: StepName)`, beside the existing string one.

## Why this shape

The same argument that made session keys typed, applied one layer out: a name
written twice is a name that can disagree with itself, and the compiler is
already holding one of the two copies. A `val` also gives a scenario split
across files something to import, which a string literal never does.

The string overloads stay because a report genuinely does read names at
runtime, and because a scenario built from a list of endpoints has nothing to
declare. This is a better default, not a prohibition.

The alternative is generated accessors — `result.placeOrder` — which needs a
processor and a build plugin, and buys a dot instead of a bracket.

## Stack

- [ ] **`spec-0012-step-name`** — `StepName`, `step(name)`, the `exec` overload
      and the `RunResult` lookup.
      Done when: a scenario declared with handles is asserted on with the same
      handles, and the string forms still work.
- [ ] **`spec-0012-adopt`** — the example, the README and the docs move to
      handles.
      Done when: no example in the repository looks a step up by string.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

Answered by the architect:

1. **`StepName` is a value class over the string**, so it costs nothing at
    runtime and cannot be confused with a `SessionKey`.
2. **Looking up a handle that never ran throws**, exactly as the string form
    does, and names the steps that did. A handle proves the name was written
    once, not that the step was reached.
3. **`http.get("/products")` keeps deriving its own name** from the path
    template when a step is not given one. A template is already written once.
