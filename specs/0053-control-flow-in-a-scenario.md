# 0053 — Control flow in a scenario

## Problem

A `Scenario` is a flat `List<Step>`, and `runOneUser` folds straight through
it. Every user does every step, once, in order. A real journey does not: it
adds three items to a cart, it pages through results until it finds one, it
pays only if the cart it built is not empty.

0024 named this and pushed it out — "no `doIf`, no `loop`, no branching within
a scenario. Control flow is its own argument." This is that argument.

The workaround is to write the loop inside a step body, which times the whole
loop as one request. A step that adds three items reports one sample at three
times the latency, and the p99 of `/cart` becomes a number describing something
that never happened.

## Not doing

- No weighted or random branching. A mix of journeys is arms (0052), and a
  random branch needs a declared seed the way `randomized` does — its own spec.
- No groups, no nested naming. A group is a reporting idea, not a control-flow
  one.
- No `asLongAs` over a target's response. A loop whose condition is a live
  request is a step nobody named; the condition reads the session.
- No `exitHereIfFailed`. A failed step already abandons the user.

## Shape

```kotlin
val checkout = scenario("checkout") {
    exec(browse, api.get("/products"))
    repeat(3) {
        exec(addItem, api.post("/cart").body("""{"sku":"anvil"}"""))
    }
    doIf({ session -> session[cartId] != null }) {
        exec(pay, api.post("/orders").expecting(201))
    }
}
```

- `Step` gains nested forms: `Step.Repeat(times, steps)` and
  `Step.When(predicate, steps)`. A scenario becomes a tree, walked rather than
  folded.
- `repeat(n)` and `during(duration)` for a count and a clock.
- `doIf(predicate)` reads the session and nothing else.
- `StepStats` gains `reached` — the users that got to the step — beside
  `count`, the requests it made.

## Why this shape

`reached` is the part that is not obvious and is the reason this is a spec
rather than a loop. Today `count` is both facts at once, because every user
runs every step exactly once. Under a loop `count` is three times the users;
under a condition it is a fraction of them. Every rate on the page is over one
of those two numbers, and a report that keeps only one of them cannot say
whether a step made few requests because few users reached it or because each
one that did made few.

A tree rather than a flat list with markers. Markers would put the matching of
`loop-start` to `loop-end` in the engine and in the report and in the plan, in
three places that can disagree; a tree makes the compiler do it once.

The cost is real and worth stating: `AGENTS.md` forbids `else` on a `when` over
a sealed type, so two new `Step` cases break every exhaustive `when` in the
engine, the plan and the reports. That is the compiler naming each site that
needs a decision, which is the reason for the rule.

## Stack

- [ ] **`spec-0053-tree`** — nested `Step` forms, the engine walking them, and
      `plan()` flattening the tree to the names it carries.
      Done when: a scenario with one nested step runs it, and the plan lists
      its steps in order with the loop's step named once.
- [ ] **`spec-0053-reached`** — `reached` on `StepStats`, recorded per user
      rather than per request, and on the page.
      Done when: a step run three times by each of ten users reports 30
      requests and 10 reached.
- [ ] **`spec-0053-repeat`** — `repeat(n)` and `during(duration)`.
      Done when: three iterations produce three samples under one name, and a
      `during` loop stops on its own clock rather than the profile's.
- [ ] **`spec-0053-doif`** — `doIf(predicate)` over the session.
      Done when: a step inside a false condition records nothing at all, and is
      not counted as a failure or a skip.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Does a step inside a false condition appear on the page?** Recommend yes,
    with `reached` at zero and no timings — an absent row reads as a step that
    was never written, and a zero row reads as one nobody took, which is the
    finding.
2. **What bounds `during`?** A user looping for two minutes inside a run that
    ends at one extends the run, because the engine already waits for the last
    user. Recommend leaving it and saying so: the alternative is cutting a user
    off mid-journey and recording a failure the target did not cause.
3. **Is an iteration's index available to the body?** Recommend yes, as the
    lambda's parameter, and recommend it is *not* put in the session: a session
    key written per iteration is state the next iteration reads, which is how a
    loop body stops being a function of the user.
4. **Do goals read `count` or `reached`?** Recommend `count` for percentiles
    and failure rates — they are over requests — and recommend a goal over
    `reached` is not added until somebody wants one.
