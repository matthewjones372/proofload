# 0107 — The way out of a plan

## Problem

`plan_schema` tells a caller that a plan "deliberately cannot say everything the Kotlin DSL
can … When a plan needs one, `emit` prints the Kotlin it was equivalent to and you continue
there." The MCP server has no `emit` tool. The document names the exit; the surface does not
have one.

`proofload-mcp/build.gradle.kts` states the module's invariant — "every tool here is a call that
command line already makes, so what a model can do is what a person at a terminal can do".
`emit` is a `proofload-cli` command, so that invariant is already false, and false for the one
command that exists to rescue a caller from the plan format.

What happens next is the part worth fixing. Benchmarking writes that carry an `expectedVersion`
needs a value from the previous response, which a plan cannot give. An agent that cannot reach
`emit` does not stop: it writes its own generator, usually a closed loop that sends the next
request when the last one answered. That measures the queue it built and reports it as the
target's — the bug this library exists to remove — and nothing in the MCP surface said so. Not
hypothetical: it is what happened when this repository's own MCP server was pointed at an
event-sourced API.

## Not doing

- **No Kotlin execution in the MCP.** Running emitted source needs a compiler and a project.
  `emit` hands over source, and the handover is the point.
- **No captures in the plan format.** That is 0106's argument. This is what a caller does when
  a plan is genuinely not enough, whatever 0106 decides.
- **No new prose file.** The warning goes where a caller already reads.

## Shape

`emit` becomes a tool, sending nothing:

```
emit {"plan": "proofload: plan/1\n…"}
```

```kotlin
// the scenario this plan is equal to; continue here
val browse = step("browse")
val checkout = scenario("checkout") { exec(browse, api.get("/products")) }
```

And the parser's refusal names the exit rather than only the mistake:

```
`capture` is not a key a plan has. A plan cannot read a value out of a response —
that needs a lambda. Call `emit` for the Kotlin this plan equals and add it there.
```

## Why this shape

Exposing `emit` is one entry in `TOOLS` and one `when` branch over an emitter that already
exists, and it restores an invariant the module claims. That is most of the value: a caller
handed working source at the moment it gets stuck does not go and build a worse generator.

The refusal text is the other half. A caller does not re-read `plan_schema` before each
mistake; it reads the error it just got. Naming `emit` there is the argument `Tool.sends`
already won — tell them at the moment it matters.

The alternative is to say nothing and let callers find `docs/cookbook.md`. Recommended against:
a caller that could find the cookbook was never the one at risk. The failure mode is a caller
confident enough to build its own tool, and only a sentence in the answer it is already reading
reaches that caller.

## Stack

- [ ] **`spec-0107-emit`** — `emit` in `TOOLS` and `calling`, over the existing emitter.
      Done when: a plan read through `benchmark` comes back as Kotlin that compiles in
      `examples`, and the tool reports it sends nothing.
- [ ] **`spec-0107-refusals`** — refusals for keys a plan cannot have name `emit`.
      Done when: a plan carrying `capture` is refused with the exit in the same sentence, and a
      test asserts the text rather than only the failure.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

- **Should `run` carry a coordinated-omission warning?** Recommend one sentence, on `run`
  alone: it is the tool a caller reaches for before giving up on the plan format. On every tool
  it is noise. Counter-argument: a caller that has decided to write its own script never calls
  `run` again, so the sentence arrives too late.
- **Does `emit` take the CLI's package flag?** Recommend defaulting to `load` and not exposing
  it; a caller pasting into a project renames it anyway.
- **Should `benchmark` ask about it?** A smoke that sees a 409 is probably looking at optimistic
  concurrency, which a plan cannot drive. Recommend it say so in its questions, as it already
  asks about a 401 and a bare id — cheap, and it lands before the caller has written anything.
