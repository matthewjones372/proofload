# 0001 — Scenarios as values

## Problem

There is no way to describe load in this repository yet. The reference point,
Gatling, asks a Kotlin team to write Scala, adopt a plugin to run in CI, and
read a bundled web app to see what happened. Teams wanting a smaller slice —
describe a scenario, run it from a `main` or a test, get honest numbers — hand
roll a thread pool and a timer instead, and end up measuring their own harness.

The description needs a shape before any of that can be answered. This spec
defines what a scenario *is*: an immutable value, built, inspected, split
across files and compared before a request is sent. Nothing in it executes.

## Not doing

- No execution engine, scheduler or virtual threads — spec 0002.
- No HTTP or protocol types; `kestrel-http` over `java.net.http` is spec 0003.
- No closed-model injection (N users looping). Open model only.
- No histogram and no report rendering — specs 0004 and 0005. `RunResult` is
  named as a return type here and designed there.
- No JUnit and no Kotest extension, and no dependency on either anywhere below
  them. A simulation runs from a `main`; both are later leaf modules.
- No feeders, no checks/assertions DSL, no pauses beyond what a profile states.
- No Pelican binding. `kestrel-pelican` implements Pelican's `ClientTransport`
  so a typed client runs under this engine; it needs 0003 first.

## Shape

```kotlin
import io.github.matthewjones372.kestrel.Scenario
import io.github.matthewjones372.kestrel.Simulation
import io.github.matthewjones372.kestrel.constantRate
import io.github.matthewjones372.kestrel.scenario
import kotlin.time.Duration.Companion.minutes

val checkout: Scenario = scenario("checkout") {
    exec("browse") { session -> session.set("cart", "empty") }
    exec("add to cart") { session -> session }
}

val simulation = Simulation(checkout, constantRate(perSecond = 50.0, over = 1.minutes))
```

The values, one line each:

- `Scenario` — a name and an ordered read-only `List<Step>`.
- `Step` — sealed; `Exec(name, Action)` and nothing else yet.
- `Action` — the interface core declares, protocol-free, so an HTTP leaf can
  implement it without core seeing a protocol type. It returns a `StepResult`.
- `StepResult` — the next `Session` and the outcome, ok or failed with a
  reason. The engine reads a value to measure; it never catches to decide.
- `Session` — per-virtual-user state, immutable, `set` returns a copy.
- `InjectionProfile` — sealed, open model: `ConstantRate`, `RampRate`. It
  states departure times, and exposes them as a pure sequence of offsets.
- `Simulation` — a scenario paired with a profile; what a runner is handed.

## Why this shape

A value has no lifecycle: no registry, nothing running at import time, and a
test can assert on a scenario without a server. The alternative is Gatling's
builder, which fires as it is called and so cannot be inspected before it runs.
It is rejected because everything downstream — filtering, composing across
files, golden-file reports — needs the description to outlive the run.

The second choice constrains the rest: a profile describes **departure times**,
not think-time after a response. A generator that waits for a response before
scheduling the next request measures a queue it created. Making the schedule a
pure function of the profile keeps coordinated omission out of the design
rather than out of the documentation.

## Stack

- [ ] **`spec-0001-scenario`** — `Scenario`, `Step`, `Action`, `Session` in
      `kestrel-core`, with contract tests through the public API.
      Done when: a two-step scenario builds, inspects and compares by value,
      and `./gradlew build` is green including `apiCheck`.
- [ ] **`spec-0001-injection`** — sealed `InjectionProfile`, open-model
      variants, and the profile-to-departure-offsets function.
      Done when: a constant rate yields the expected offsets as values, with
      no clock involved.
- [ ] **`spec-0001-simulation`** — `Simulation`, plus the README and
      `docs/modules.md` entry for the value model.
      Done when: the README example compiles as written.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

None left. The four this draft opened were answered before it was committed,
and the answers are in the shape above:

1. **`Action` returns a value, not a mutated session.** `StepResult` carries
    the next session and the outcome, so measuring a failure never depends on
    catching a throwable.
2. **`Session` is in.** Retrofitting per-user state through a value model
    touches every type in it.
3. **`kestrel-core.api` is regenerated in the first PR** and its diff read as a
    golden file, not rubber-stamped.
4. **`Pause` and `Group` wait** for an engine that can honour them.
