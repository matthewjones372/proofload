# 0091 — A plan from a contract

## Problem

Writing the first load test against a service is transcription. The paths, the
verbs, the status codes and a plausible body all exist already — in the OpenAPI
document the service publishes, or, for a Pelican service, in the endpoint
values the server is built from — and someone types them a second time into a
scenario. 0010 said as much and deliberately left it: *"No OpenAPI import.
Generating a smoke scenario from a document is a later spec, and a different
idea."* This is that spec.

It is also the step where a caller that is not a person gets stuck. Under 0089
a plan is a file, but inventing a legal body for `POST /orders` from nothing is
guessing, and a run built on guessed payloads measures the target's validation
layer rejecting them.

The blocker written down in `ROADMAP.md` — that an importer "wants a YAML
parser" — is gone. `pelican-import` reads OpenAPI 3.1, 3.2 and Swagger 2 into
the same endpoint model `pelican-core` holds, on snakeyaml-engine, as a
build-time tool. There is nothing left to write but the half that turns
endpoints into steps.

## Not doing

- **No ordering.** A document says what endpoints exist, never that browse
  precedes checkout. The generated plan is a flat list in document order and
  says in a comment that a journey is the caller's to write. Traffic-derived
  order is 0080's job and stays there.
- **No auth.** A token is not in the document. The plan emits a named
  placeholder that fails validation until it is filled — not a guess, and not a
  blank that runs and 401s ten thousand times.
- **No mutation by default.** Generated plans include `GET` only unless asked;
  a generated `DELETE /orders/{id}` loop against staging is somebody's evening.
- **No change to Pelican.** If the endpoint model needs something, that is a
  spec in that repository.
- **Nothing on a measured path.** Both readers are build-time, like
  `kestrel-record`.

## Shape

Two sources, one output — an 0089 plan a person can read and edit:

```bash
kestrel from-openapi orders.yaml --out plan.yaml --methods get,post
```

```kotlin
import io.github.matthewjones372.kestrel.pelican.planFrom

// A Pelican service: the descriptions are values already on the classpath.
val plan = planFrom(OrdersEndpoints.all, baseUrl = "https://orders.internal")
```

What the contract buys beyond the paths:

- **Bodies that are legal.** A Pelican input carries its constraints —
  `between(1, 100)` is both the check and the schema's `minimum`/`maximum` — so
  a generated value satisfies the validation rather than probing it.
- **Failures with a verdict already attached.** `orFail` puts a declared
  failure in the endpoint's type. A declared `404` is the service working; an
  undeclared `500` is a defect. The generated goals say so, and the 0087
  summary can report `undeclared: 41` rather than a failure count that lumps
  them together.

## Why this shape

Emitting a plan file rather than Kotlin is the choice worth arguing. Kotlin is
what `kestrel-record` emits and what a person eventually wants; a plan file is
editable by whatever produced the request, re-validated in milliseconds, and
one `kestrel emit --kotlin` away from the source anyway. Recommend the plan,
with `emit` as the graduation — it puts one generator behind both outputs
instead of two.

The declared-versus-undeclared split is the part that is a measurement
improvement and not a convenience. Every other load tool has to be told which
statuses are acceptable, by hand, per step; a typed contract already knows, and
a step that fails under its own declared name is 0026 and 0063's shape. It is
the strongest argument for the Pelican pairing and should be built even if the
document importer is cut.

## Stack

- [ ] **`spec-0091-endpoints`** — `planFrom(endpoints, baseUrl)` in
      `kestrel-pelican`: a step per endpoint, named by path template.
      Done when: six endpoints yield six steps, and a path parameter yields one
      row rather than one per value.
- [ ] **`spec-0091-data`** — constraint-satisfying values per input, seeded, so
      two runs of a generator agree.
      Done when: a `between(1, 100)` parameter never emits 0 or 101 across a
      thousand draws from a fixed seed.
- [ ] **`spec-0091-declared`** — declared failures as expected outcomes,
      undeclared statuses counted apart.
      Done when: a run against a stub returning a declared 404 and an
      undeclared 500 reports one of each, separately.
- [ ] **`spec-0091-openapi`** — `kestrel-openapi` over `pelican-import`, its
      dependency test, and the `from-openapi` command.
      Done when: a document with fifteen paths produces a plan that `kestrel
      validate` accepts, with the auth placeholder unfilled and refusing.

## Acceptance

```bash
./gradlew build
build/install/kestrel/bin/kestrel from-openapi specs/fixtures/orders.yaml --out plan.yaml
build/install/kestrel/bin/kestrel validate plan.yaml   # fails: token placeholder
```

## Open questions

> Answered 2026-09-04, each on the recommendation in its own bullet. The
> reasoning is left standing rather than deleted: a decision is easier to
> reopen when the alternative it beat is still written down.

- **Does `kestrel-openapi` depend on `pelican-import`, or does Kestrel read the
  document itself?** Recommend depending on it. It is the same author, it is
  build-time only, and a second OpenAPI reader in the same house that disagrees
  about a schema is worse than a coupled release train.
- **What rate does a generated plan carry?** Recommend a deliberately small one
  — 1/s over 10s, a smoke — so the generated artefact is never the thing that
  hurt something. The caller raises it on purpose, under 0088's ceiling.
- **Do generated goals exist at all?** Recommend one per step, `p99` against a
  placeholder the caller must replace, so the plan does not validate green
  while asserting nothing.
