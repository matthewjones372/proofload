# 0019 — What was asked for

## Problem

A `RunResult` records what happened and nothing about what was intended. The
page cannot name the scenario, cannot show the load shape, and — the one that
matters — cannot say whether the run sent what the profile asked it to.

That last gap is a hole in the honesty argument. A profile that promised 480
users and a run that sent 300 look identical on the page today: three hundred
requests, some percentiles, no sign that a quarter of the intended load never
left. Every latency on that page is then describing a lighter test than the one
that was asked for.

Proofload has an advantage here that Gatling does not: a scenario is a value and a
profile is a value, so the description is already in hand at the end of a run.

## Not doing

- No per-stage results. Which stage a request belonged to needs a timeline in
  the recorder, and that is its own change.
- No serialising of actions. A plan carries names and the profile, not the code
  a step runs.
- No change to the markdown or job summary in this spec.
- No planned-versus-actual assertion. The page reports it; a test asserts what
  it wants.

## Shape

```kotlin
result.plan.scenario        // "checkout"
result.plan.steps           // ["/products/{shopper}", "/cart", "/pay"]
result.plan.profile         // the stages, as a value
result.plan.plannedRequests // 1,440
```

On the page, above the numbers:

> **checkout** — 3 steps, 120/s held for 4s. Planned 480 users, 1,440 requests.
> **The run sent all 1,440.**

and a small chart of the shape itself — rate against time, drawn from the
stages, flat for a hold and sloped for a ramp.

- `Plan` — the scenario's name, its step names in order, and the profile.
- `RunResult.plan`, filled by the engine, defaulted for a hand-built result.
- The shortfall sentence, when what was sent is under what was planned.

## Why this shape

The plan is small and already exists: two names and a profile made of stages.
Carrying it costs nothing and closes the gap between "here is what happened"
and "here is what was asked for", which is the only way a reader can tell a
light run from a fast target.

The shape chart is drawn from the profile rather than from recorded traffic on
purpose. It is the *intent*, and putting it beside the results is what makes a
shortfall visible rather than something a reader has to work out.

## Stack

- [ ] **`spec-0019-plan`** — `Plan`, `RunResult.plan`, and the engine filling
      it from the simulation.
      Done when: a run reports the scenario's name, its steps in order, and a
      planned request count that matches the profile.
- [ ] **`spec-0019-page`** — the plan header, the shortfall sentence, and the
      shape chart.
      Done when: a run that sent everything says so, a run that sent less says
      how much less, and the chart shows a ramp as a slope.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

Answered by the architect:

1. **Planned requests is users times steps.** It is an upper bound: a scenario
    that abandons users sends fewer, and that is exactly what the page should
    make visible rather than hide.
2. **A hand-built `RunResult` gets an empty plan** rather than a required
    argument, so a report can still be built from samples in a test.
3. **The shape chart is drawn even for a single stage.** A flat line is a fact
    about the run, and a chart that appears only sometimes is one a reader
    stops looking for.
