# 0096 — Not one row repeated

## Problem

0015 made a feeder a function of the user's number and 0054 let it read a CSV,
which covers the case where the data already exists. It leaves the case where it
does not, and there the state of the art in this repository is a thousand-row
file cycled for a million users.

That is a measurement problem, not an inconvenience. A target asked for the same
thousand keys all run keeps them in every cache it has, so the p99 on the page is
partly a hit rate the test invented. The obvious correction — a random value per
user — is wrong in the opposite direction and less obviously: uniformly random
keys over a large space miss every cache, and real traffic does neither. What
decides the number is **cardinality and skew**, and Kestrel currently gives a
user no way to say either.

There are two smaller problems underneath. A value drawn inside a step body
allocates on the timed path, and the collection pause it eventually buys is
recorded in `hiccups` and read as the target's latency. And a value drawn from a
shared generator makes fifty thousand virtual threads contend on one RNG, in the
one place this tool cannot afford a lock.

## Not doing

- **No kotest-property on a main classpath.** `Arb` is built to find bugs, so it
  is deliberately biased towards edge cases — empty strings, `MIN_VALUE`, the
  boundary. That bias is correct for a property test and wrong for load, where
  the point is traffic that looks like traffic. An adapter for callers who want
  it anyway is the last stack entry, in a module of its own.
- **No faker, no dictionaries of real names, no locale data.** A megabyte of
  sample surnames is a dependency and a licence question, for realism nothing
  here measures.
- **No stateful generator.** Nothing shared, nothing that has to be reset
  between runs, nothing to contend on.
- **No sequences that depend on what the target returned.** That is a capture
  and it already exists.
- **No change to `csv`.** A file is still the answer where the data is real.

## Shape

`kestrel-arbs`, core and the JDK, generators that are values:

```kotlin
import io.github.matthewjones372.kestrel.arbs.digits
import io.github.matthewjones372.kestrel.arbs.oneOf
import io.github.matthewjones372.kestrel.arbs.uuids
import io.github.matthewjones372.kestrel.arbs.zipf

val customerId = zipf(keys = 1_000_000, skew = 1.1)   // a few keys are most of the traffic
val basket     = oneOf("anvil", "rocket", "birdseed")
val idempotency = uuids()

// Drawn in the feeder, before the departure — never in the step body, which is
// the measured path.
val checkout = scenario("checkout") {
    exec(placeOrder, api.post("/orders").body(order))
}.feeding(customer, cart) { user -> customerId at user to (basket at user) }
```

Three rules the module holds to:

- **`at(userNumber)`, never `next()`.** A generator is a pure function of the
  user's number, so a run replays, a failure at user 8,412 is re-derivable, and
  no two threads share a source. 0015's shape, with the arithmetic filled in.
- **Drawn where the feeder runs**, before the departure, never inside a step
  body on the measured path.
- **The seed and the shape travel into the result**, the way 0034 records the
  arrivals it drew and 0067 the think time — so the page can say what
  cardinality and skew the numbers were measured under, and a comparison against
  a baseline can refuse two runs that drew differently.

## Why this shape

`zipf` is the reason to build this rather than document `Random(user)` in the
cookbook. Uniform and constant are the two distributions a user can already
write, and they are the two that are wrong; the parameter that changes a p99 is
the skew, and nothing in the tool currently names it. A module whose headline
generator is `uuids()` would not have been worth a module.

Splatting hashing over the user number rather than seeding a `Random` per user is
recommended for the same reason `at` is: a `Random` instance per user is an
allocation per user on the path that books departures, and a mixing function is
arithmetic. The alternative — `Random(user.toLong() * PRIME)` — is one line and
allocates; recommend the hash, and let the test that proves the distribution
decide whether the difference is visible.

This overlaps [0091](0091-a-plan-from-a-contract.md) and does not duplicate it.
0091 generates values that are **legal** — inside the constraints a contract
declares. This generates values that are **shaped** — the right cardinality and
skew. `spec-0091-data` should produce these types rather than its own.

## Stack

- [ ] **`spec-0096-module`** — `kestrel-arbs`, its dependency test, the `Arb<T>`
      value with `at`, `map` and `oneOf`, on a mixing function rather than a
      seeded `Random`.
      Done when: `arb at 7` returns the same value in two JVMs, and drawing a
      million values allocates nothing per draw that 0093's harness can see.
- [ ] **`spec-0096-shapes`** — `zipf`, `uniform`, `digits`, `uuids`,
      `weighted`.
      Done when: a million draws from `zipf(1_000_000, skew = 1.1)` put the top
      1% of keys within a few points of the share the exponent predicts.
- [ ] **`spec-0096-recorded`** — the drawn shape and seed carried into
      `RunResult`, on the page, and refused across a baseline comparison that
      drew differently.

      Nothing about a feeder reaches `Plan` today: `Arm.feeder` is a bare
      `fun interface` and `PlannedArm` is built from the scenario tree and the
      arm alone, so this needs a **new declaration point in core** —
      `Arm.drawn` and `Simulation.drawing(...)`, mirroring `Arm.thinkSeed` and
      `Simulation.thinkingFrom`. Downstream it moves `PlannedArm.unlike`, a
      baseline format bump (8 to 9, `READABLE` extended, or the field vanishes
      on round-trip), and the two report modules. That is more than one branch:
      split it when it is cut.

      It moves **no goldens**, which is worth saying because the first estimate
      here said fifteen. Absent means no claim: a run that declared no shape
      renders byte for byte as it did, the way a run that warmed nothing prints
      no warm-up line. The existing goldens then assert that the new field is
      silent when unset, which is a better thing for them to be doing than
      carrying a line every run had to grow.

      **An absent shape never refuses.** Two runs that both declared shapes are
      refused where those differ; a run that declared none compares exactly as
      it does today. Every stored baseline predates this field, so the strict
      reading would break every existing comparison the day somebody adopts a
      generator — which is the version people route around rather than adopt.
      The cost is accepted and stated here: moving a CSV-fed run onto a `zipf`
      feeder is a change no comparison will flag.

      Done when: comparing a run drawn at skew 1.1 against one drawn at 0.8 says
      so rather than reporting a regression, and comparing either against a
      baseline that declared nothing still compares.
- [ ] **`spec-0096-kotest`** — `kestrel-arbs-kotest`: `kotest.Arb<T>.shaped()`
      for callers who want the library's generators anyway.
      Done when: the adapter is one file, and its module's dependency test shows
      the property library is on no other module's classpath.

## Acceptance

```bash
./gradlew build
```

The allocation claim is a test inside that build, not a benchmark beside it:
a million draws measured with `ThreadMXBean.getThreadAllocatedBytes`, asserted
under a byte each. 0093's `footprint` harness is unbuilt, and an acceptance
block naming a task nobody has written is one nobody can run.

## Open questions

> Answered 2026-09-04, each on the recommendation in its own bullet. The
> reasoning is left standing rather than deleted: a decision is easier to
> reopen when the alternative it beat is still written down.

- **Where does `Shape` live?** In `kestrel-core`, not in `kestrel-arbs` — not a
  choice so much as the layering law answering it. The shape has to reach
  `Plan`, `Plan` is a core value, and a leaf module cannot put a type of its own
  inside one. The alternatives were a second `Shape` in core, which is two
  fields to keep in step, or a stringly-typed `drawing(description, seed)`.
  `Arb.shape` still answers a `Shape`; the class simply lives one module down.

- **Is `Arb` the right name, given kotest's means something else?** Recommend
  keeping it and naming the difference in the KDoc's first line. The word is
  what people will search for, and a synonym invented to avoid a collision is a
  second thing to learn.
- **Does `zipf` return a key or an index?** Recommend an index — a `Long` rank —
  with `map` turning it into whatever the caller's keyspace is. A generator that
  formats strings has guessed the target's id scheme.
- **Does the page draw the distribution?** Recommend not yet: state the shape
  and the seed in words. A histogram of what the test sent is a chart about the
  test, and 0018's rule is that the page's charts are about the target.
