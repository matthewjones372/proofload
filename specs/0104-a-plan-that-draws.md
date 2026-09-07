# 0104 — A plan that draws

## Problem

`from_openapi` reads `/products/{sku}`, picks one value the schema calls legal,
and writes `/products/2`. Every user then asks for product 2. That is a
measurement of one row and one cache line, and
[0096](0096-not-one-row-repeated.md) exists because it is the wrong shape:
cardinality and skew are the parameters that move a p99.

The plan format cannot say otherwise. `kestrel-arbs` has `uniform`, `zipf`,
`oneOf`, `digits` and `uuids`, the cookbook teaches them, and none of it is
reachable from a file — so the generated plan a newcomer reads first teaches
them to hammer one row, and the page they would need is the one they have not
found yet.

The machinery is already there and this is smaller than it looks. `{name}` in a
path **and** in a body is filled from the session per user at run time, failing
as `UnfilledPath` when nothing is under the key. What is missing is any way for
a plan to put something there: a plan cannot declare a feeder.

## Not doing

- **No new module.** `kestrel-arbs` depends on core and nothing else, so
  `kestrel-plan` can carry it without a third-party jar arriving — which is
  what forced a separate module for Kafka and gRPC and does not apply here.
- **No generator of this file's own.** The names in a plan are the names in the
  cookbook, so a reader graduating to `emit` finds the same vocabulary.
- **No captures.** Drawing fills the session before a run; reading a value out
  of a response is a lambda and stays 0089's refusal.
- **No arbitrary expressions.** A named generator with named arguments. A file
  that grew arithmetic would be a worse language for the same job.

## Shape

Drawn once per user, read by any step that names the key:

```yaml
kestrel:  plan/1
baseUrl:  http://localhost:8742
scenario: checkout
seed: 0
draw:
  sku:      {uniform: 500}
  customer: {zipf: {keys: 1000000, skew: 1.1}}
  basket:   {oneOf: [anvil, rocket, birdseed]}
steps:
  - name: open product
    get: /products/{sku}
  - name: place order
    post: /checkout
    body: '{"customer":"{customer}","item":"{basket}"}'
```

Lowering to what the cookbook already writes by hand:

```kotlin
.fedBy(feed(sku) { uniform(keys = 500) at it } + …)
.drawing(uniform(keys = 500).shape, …)
```

## Why this shape

A `draw:` block above the steps rather than a generator inline on each, because
one key is read by several steps and a value drawn twice is two different
values. It is the shape a feeder already has.

One key names the generator and carries its arguments, which is the idiom a
step already uses for its verb. The call form `uniform(keys: 500)` was drafted
first and is not YAML — the nested `: ` fails to parse — and `uniform(500)`
parses only by hiding the argument's name. A nested map costs two braces and
says what it means.

Every drawn value reaches the session as a **`String`**, mapped through
`Arb.map`. Not a detail: interpolation reads `sessionKey<String>(name)`, and a
key of that name holding a `Long` is a *throw* rather than a failed step —
nobody declared it. `map` leaves the shape alone on purpose, so
`uniform(keys=500)` still prints as itself.

`drawing(...)` is the half that earns this. The shapes travel onto the report
as `Data: zipf(keys=1000000, skew=1.1), seed 0.`, into the baseline, and into a
comparison that refuses two runs drawn differently rather than reporting the
cache hit rate one of them bought as a regression. A plan that draws gets that
for free; a plan that substitutes one id can never have it.

The alternative is leaving this to `emit` — draw in Kotlin or not at all.
Recommended against: the generated plan is the artefact most people read first,
and it currently teaches the opposite of 0096.

## Stack

- [x] **`spec-0104-drawn`** — `draw:` in the model, reader and writer, lowered
      to a feeder and `drawing(...)`.
      Done when: two users of a drawn plan send two different paths, the run
      records the shape, and a key nobody drew still fails as `UnfilledPath`.
- [x] **`spec-0104-from-a-contract`** — `from_openapi` and `planFrom` writing a
      draw bounded by the schema instead of one substituted value.
      Done when: a `{sku}` with `minimum: 1, maximum: 500` becomes
      `uniform(keys: 500)` and the template survives into the plan.
      Written as `{uniform: {from: 1, to: 500}}`, per the answered question
      below. An `enum` draws every value it lists rather than the first, and a
      parameter the contract does not bound at both ends is substituted as
      before — inventing a range is inventing a cardinality.
- [ ] **`spec-0104-emit`** — the emitter printing the arbs the cookbook writes.
      Done when: an emitted drawn plan compiles in `examples` and names
      `kestrel-arbs` in its imports.
- [ ] **`spec-0104-taught`** — `plan_schema`, `docs/cookbook.md`, and the
      question `benchmark` should ask about a literal id.
      Done when: a plan whose path carries a bare id is asked whether that is
      one row on purpose.

## Acceptance

```bash
./gradlew build
```

## Open questions

- **Does `from_openapi` draw by default?** Recommend yes — the default is what
  gets read, and one substituted id is the anti-pattern 0096 named. It changes
  every generated plan, so it wants saying out loud rather than discovering.
- **Is the seed per key or per plan?** Recommend a plan-level `seed:` with each
  key seeded from it and its own name. Two keys sharing a seed and a shape draw
  the *same* values, which is a bug nobody would see: customer 41 always buying
  item 41.
- **Answered by building it: both.** `keys` alone cannot say what a contract
  says. `{uniform: 500}` draws 0 to 499, and a schema stating
  `minimum: 1, maximum: 500` means 1 to 500 — so `uniform` also takes
  `{from: 1, to: 500}`, lowering to `uniform(keys = 500).map { it + from }`
  with the shape left where it was. The recommendation above was wrong and is
  left standing rather than deleted.
- **Answered while drafting: the syntax is a nested map**, because the call
  form is not YAML. `oneOf` therefore takes a real list and a value with a
  comma in it needs no rule of its own.
