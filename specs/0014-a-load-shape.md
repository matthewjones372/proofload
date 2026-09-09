# 0014 — A load shape

## Problem

A profile is one stage. `constantRate` holds a rate for a window, `rampRate`
climbs across one, and there is no way to say the shape almost every real test
wants: climb to a rate, hold it while the target settles, and come back down.

The workaround is three simulations run in sequence, which measures three runs
rather than one — three sets of percentiles, three warm-ups, and no answer to
"what did the hold look like once the ramp had finished".

## Not doing

- No closed model. N users looping is a different shape and its own spec.
- No shape carried on `@LoadTest`. A rate belongs in Kotlin, where it can be a
  constant two tests share; an annotation attribute cannot.
- No CI scaling knob. Worth having, and it is a separate change.
- No per-stage reporting. A run is still one `RunResult`; which stage a request
  belonged to is a later spec if anyone wants it.

## Shape

```kotlin
import io.github.matthewjones372.proofload.hold
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.rampRate
import io.github.matthewjones372.proofload.then
import io.github.matthewjones372.proofload.thenRampTo
import kotlin.time.Duration.Companion.minutes

val soak = rampRate(from = 0.perSecond, to = 200.perSecond, over = 1.minutes)
    .then(hold(200.perSecond, over = 10.minutes))
    .thenRampTo(0.perSecond, over = 1.minutes)

val simulation = checkout.injecting(soak)
```

- `InjectionProfile.Stages(stages)` — a sealed variant holding profiles in
  order. Its departures are each stage's own offsets, shifted by the stages
  before it.
- `then` — sequences two profiles, flattening rather than nesting.
- `hold(rate, over)` — `constantRate` under a name that reads in a chain.
- `thenRampTo(rate, over)` — ramps from where the receiver ended.
- `endRate` — what a profile is running at when it finishes, which is what
  `thenRampTo` reads.

## Why this shape

Stages are values in a list, so a shape can be built, inspected and compared
before anything is sent, and `userCount()` still answers for the whole run
before a request leaves. Departures stay a pure function of the shape: stage
two's offsets are stage two's own, plus a constant.

`thenRampTo` says in its name that it reads the receiver, rather than a bare
`rampTo` whose starting rate would depend on where it happened to be written.
A ramp that means one thing in a chain and another alone is the kind of
context-dependence that makes a DSL guessable rather than readable.

## Stack

- [ ] **`spec-0014-stages`** — `Stages`, `then`, `endRate`, and departures and
      `userCount` across stages.
      Done when: a ramp followed by a hold departs in one continuous sequence,
      and its user count is the sum of the two.
- [ ] **`spec-0014-chaining`** — `hold`, `thenRampTo`, and the README.
      Done when: the soak above builds, and its last stage starts at the rate
      the hold was running at.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

Answered by the architect:

1. **`then` flattens.** Nesting would make two shapes that describe the same
    load compare unequal, and a value that cannot be compared is half a value.
2. **A stage of zero duration is kept, not dropped.** It contributes nothing
    and says something: a shape written with one is a shape somebody meant.
3. **`Stages.over` is the sum of its stages**, so a caller can still ask how
    long a run will take without walking the list.
