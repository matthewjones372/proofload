# 0106 — A plan that answers the server

## Problem

A plan cannot benchmark an API that makes the client echo something back. Monopoly Deal's
`POST /games/{id}/commands` needs the `version` from the previous response for its
optimistic-concurrency check, a fresh idempotency key per attempt, and a move legal in the
state that response describes. A plan has none of them, so the run that mattered — the write
path, the one that appends events — was measured by a bespoke harness instead.

That is a class, not a quirk: a version echo is how event-sourced services accept writes, and
idempotency keys are ordinary on order and payment APIs. `benchmark`, `smoke` and `run` reach
every read endpoint of such a service and none of its writes. The Kotlin DSL already does it
— `capture(orderId) { it.header("location") }` — so the gap is only in the plan format, which
is the one surface the MCP server (0092) can run.

## Not doing

- **No control flow.** `if`, retry-on-409 and "pick a legal move" stay Kotlin. 0089 settled
  that a file which grows an `if` has become a bad language.
- **No second execution model.** A plan lowers to the same captures the DSL builds.
- **No general JSONPath.** A dotted field path, or nothing.
- **No response assertions.** Reading a field to send it back is not asserting on it.

## Shape

A step names what it takes from its response; a later step spends it. The names are session
keys — the ones `draw` already fills.

```yaml
draw:
  attempt: {uuids: {}}
steps:
  - name: open game
    post: /solo
    body: '{"name":"bench","bots":["greedy"]}'
    capture:
      version: version          # a dotted path into the JSON body
      game:    gameId
  - name: draw cards
    post: '/games/{game}/commands'
    body: '{"command":{"DrawCards":{}},"expectedVersion":{version},"idempotencyKey":"{attempt}"}'
    capture:
      version: version          # the echo: this response feeds the next step
```

```kotlin
.capture(version) { it.json("version") }
```

## Why this shape

`capture:` is a map of session key to source, the shape `draw:` already has, so a plan has one
way to name a value and two ways to fill it. The alternative — a per-step `from:` block with
its own syntax — is recommended against: two vocabularies for "a name holding a string".

A dotted path rather than JSONPath, because the path is a lookup and every step of it can be
named when it misses; JSONPath brings the filters and wildcards this file may not have.
Captured values are `String` like drawn ones, for 0104's reason.

What this still cannot do is choose a move legal in the state just returned. That needs a
lambda and stays Kotlin. So this makes a *fixed* command sequence expressible, not a playing
client — which covers the version echo and the idempotency key, the part that blocks the
common case.

## Stack

- [ ] **`spec-0106-capture`** — `capture:` in the model, reader and writer, lowered to the
      DSL's existing capture.
      Done when: a two-step plan sends the id the first step returned, and a path that misses
      fails that step by name rather than sending a literal `{version}`.
- [ ] **`spec-0106-emit`** — the emitter printing `capture(...)` for a captured plan.
      Done when: an emitted plan compiles in `examples` and draws the same values.
- [ ] **`spec-0106-taught`** — `plan_schema`, `docs/cookbook.md`, and a `benchmark` question
      about a write path that echoes a version.
      Done when: `plan_schema` documents `capture` and carries a worked plan using it.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

- **Does this reopen 0089?** One keyword, no control flow, and the DSL already has it — so the
  recommendation is no. But 0089 drew its line at "a strict subset of what the DSL can build"
  and 0104 named captures as what a plan deliberately lacks. If that line is meant to hold,
  the alternative is to give the MCP server a way to run a compiled scenario: much larger, and
  a different spec.
- **Headers and status too, or body only?** Recommend body-only first; `header:` is the
  obvious second and can wait for someone who wants it.
- **What if one user's capture fails?** Recommend the step fails under a new `Reason` and that
  user abandons, as `UnfilledPath` does, rather than refusing the run.
- **Should `smoke` fill captures?** It must, or the debug loop fails a plan the run sends fine
  — the bug just fixed for `draw`.
