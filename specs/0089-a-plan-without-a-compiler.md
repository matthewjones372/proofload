# 0089 — A plan without a compiler

## Problem

Every route into Kestrel runs through `kotlinc`. To ask "does `/checkout` hold
at 50 a second" you need a Gradle project, the four coordinates, a test class
and a compile. For a person adding a load test to a service they already build,
that is right — it is what buys the typed captures and the rename that breaks
the build.

For a caller that is not that person it is the whole cost. A program writing a
scenario — a script, an operator, an agent under 0092 — has an inner loop of
compile errors against a DSL it cannot see, and no way to hand a plan to anyone
else without handing them a repository. The tool has no artefact smaller than a
project.

## Not doing

- **No second execution model.** The file parses into the same `Scenario` and
  the same plan value the DSL builds. If a feature is not reachable as a value
  it is not reachable from a file either.
- **No expressions, no control flow, no scripting.** `repeat`, `doIf` and a
  step body are Kotlin. A file that grows an `if` has become a bad language.
- **No parser in `kestrel-core`.** The reader is a leaf module.
- **Not a migration.** The Kotlin DSL stays the way anything non-trivial is
  written, and the file says so by being unable to express one.

## Shape

A plan is data, and a strict subset of what the DSL can build:

```json
{ "kestrel": "plan/1",
  "baseUrl": "https://orders.internal",
  "scenario": "checkout",
  "steps": [
    { "name": "browse", "get": "/products" },
    { "name": "place order", "post": "/orders",
      "body": "{\"cart\":\"1 anvil\"}", "expecting": 201 }
  ],
  "load": { "rate": "50/s", "over": "1m" },
  "goals": [ { "step": "place order", "p99": "200ms" } ] }
```

Read it, and run it:

```bash
kestrel validate plan.json           # parses, resolves, sends nothing
kestrel preview  plan.json           # 0088: 3,000 requests, 1m, orders.internal
kestrel run      plan.json --json    # 0087 summary on stdout; exit code is the verdict
kestrel emit     plan.json --kotlin  # the same plan as DSL source, to grow into
```

`validate` is the one that matters most: an unknown key, a goal naming a step
that does not exist, or a rate the limits refuse is an error with a line and a
name, before anything is sent.

## Why this shape

`validate` and `emit` are what stop this being a worse DSL. A caller iterates
against a parser rather than a compiler — the same errors, a hundred times
faster — and the moment the plan needs a capture or a conditional, `emit`
prints the Kotlin it was equivalent to and the caller moves into the real DSL
with the paths already written. `kestrel-record` already emits source from a
HAR, so the machinery and the precedent both exist.

The format should be **YAML read by snakeyaml-engine in a leaf module**, which
is Pelican's answer in `pelican-import` and worth copying rather than
re-deciding: YAML 1.2 is a superset of JSON, so one dependency reads both, a
generated plan can be JSON and a hand-edited one can carry comments. The
alternative is a hand-rolled JSON reader in core, avoiding the dependency; not
recommended, because the failure mode of a hand-rolled parser is a bad error
message and this file's error messages are its main feature.

Exit code as verdict is what makes `kestrel run` usable from a shell and a CI
step with no JSON reader in sight: 0 met every goal, 1 missed one, 2 the
generator fell behind so the answer is not the target's, 3 refused.

## Stack

- [ ] **`spec-0089-model`** — the plan as a value in core, and lowering it to a
      `Scenario` and a plan.
      Done when: a plan value and the equivalent DSL produce equal scenarios.
- [ ] **`spec-0089-reader`** — `kestrel-plan`, snakeyaml-engine, its dependency
      test, and errors carrying a line and a key.
      Done when: an unknown key names itself and its line, and a goal on a
      missing step fails before any transport is built.
- [ ] **`spec-0089-cli`** — `kestrel-cli`: `validate`, `preview`, `run`, the
      `--json` flag and the exit codes.
      Done when: `run` on a plan that misses a goal exits 1 and prints an 0087
      summary and nothing else on stdout.
- [ ] **`spec-0089-emit`** — `emit --kotlin`, over `kestrel-record`'s emitter.
      Done when: the emitted source for a golden plan compiles in `examples`
      and runs.

## Acceptance

```bash
./gradlew build
./gradlew :kestrel-cli:installDist
build/install/kestrel/bin/kestrel validate specs/fixtures/checkout.yaml
build/install/kestrel/bin/kestrel run specs/fixtures/checkout.yaml --json
```

## Open questions

> Answered 2026-09-04, each on the recommendation in its own bullet. The
> reasoning is left standing rather than deleted: a decision is easier to
> reopen when the alternative it beat is still written down.

- **How much of the DSL does the subset cover?** Recommend: steps, HTTP verbs,
  headers, bodies, `expecting`, stages, think time, goals — and nothing that
  needs a lambda. Captures are the hard call: a capture is a typed session key,
  and an untyped string one in a file is the "string keys to keep in sync" the
  README opens by rejecting. Recommend leaving them out of `plan/1` and letting
  `emit` be the answer.
- **One scenario per file or a mix (0052)?** Recommend one, with a `mix` key
  deferred to `plan/2`.
- **Is the CLI a published artefact or a `./gradlew run`?** Recommend a
  published fat jar with a `kestrel` launcher: a caller that has to clone the
  repository has not escaped the compiler.
