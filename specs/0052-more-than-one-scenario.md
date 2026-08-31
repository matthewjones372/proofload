# 0052 — More than one scenario in a run

## Problem

`Simulation` holds one `Scenario` and one `InjectionProfile`. Production
traffic is a mix — mostly browsing, some searching, a little checking out —
and the interesting behaviour is what those do to each other: a search that
is fine alone and evicts the cache the checkout depends on.

Today there are two workarounds and both are wrong. Running each scenario in
its own run measures three services that never met. Folding every journey into
one scenario makes every user do everything, so the ratio is 1:1:1 whatever the
real mix is, and the report's rows describe a user nobody has.

This is `setUp(a.inject(...), b.inject(...))`, which is the first thing anyone
arriving from Gatling reaches for.

## Not doing

- No per-arm goals. A goal names a step, and a step already belongs to exactly
  one arm.
- No weighted branching inside a scenario. That is a different way to express a
  mix and a worse one — see **Why this shape**.
- No per-arm reports. One run is one page, with the arm on each row.
- No change to `hiccups`, `arrivals` or `fellBehind`. Those are the run's, and
  a run is still one thing.

## Shape

A simulation becomes one or more arms, and the one-armed case is what `at`
already returns, so nothing existing changes at the call site:

```kotlin
val mixed = browse.at(400.perSecond, over = 10.minutes) +
    search.at(80.perSecond, over = 10.minutes) +
    checkout.at(20.perSecond, over = 10.minutes)

mixed.userCount()          // 3,000,000, before anything is sent
kestrel.run(mixed)
```

- `Arm(scenario, profile, feeder)`, and `Simulation(arms: List<Arm>)`.
- One departure schedule: the arms' departures are merged and ordered, so the
  target sees the mix rather than three runs that happen to overlap.
- `plan()` carries every arm, so a comparison refuses a run whose mix changed.

## Why this shape

Arms rather than a weighted branch inside one scenario. A branch gives each
user one journey drawn from a distribution, so the mix is only right on average
and a short run gets whatever the draw gave it; arms give each journey its own
rate line, which is the thing a capacity plan is actually written in.

`+` rather than a `mix(...)` function because a simulation is already a value
and the one-armed case has to keep working — `checkout.at(50.perSecond, over =
1.minutes)` appears in the README, the docs and every example.

Step names stay unique across the run. Two arms with a `pay` step would have to
either merge into one row, which describes neither, or be silently qualified,
which breaks `result[pay]`. Refusing at build time costs a rename and nothing
else.

## Stack

- [ ] **`spec-0052-arms`** — `Arm`, `Simulation` over a list of them, `+`, and
      the refusal when two arms share a step name.
      Done when: a one-armed simulation behaves exactly as today, and two arms
      sharing a step name fail to build with both names in the message.
- [ ] **`spec-0052-plan`** — `plan()` over arms, and `NotComparable` naming the
      arm that differs.
      Done when: a run of a two-arm mix will not compare against a one-arm
      baseline, and says which arm is missing.
- [ ] **`spec-0052-engine`** — the engine merging arms into one schedule, each
      arm fed from its own feeder.
      Done when: a two-arm run departs in the merged order, and each arm's user
      numbers start at zero.
- [ ] **`spec-0052-report`** — the arm on each step row, and the mix in the
      plan view.
      Done when: the page names the arm beside each step and prints the ratio
      the plan asked for beside the one that departed.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **What happens to `simulation.profile`?** It is a documented call —
    `simulation.profile.userCount()` is in the README — and a list of arms has
    no single profile. Recommend moving `userCount()` and `over` onto
    `Simulation` (summing and maxing across arms) and leaving `profile` on
    `Arm`. The README changes; the call still reads.
2. **Is the feeder per arm or per run?** Recommend per arm, each with its own
    user numbering from zero, so an arm's data is reproducible whatever the
    other arms do. A run-wide feeder would make arm two's data depend on arm
    one's rate.
3. **What does `sustainable()` do to a mix?** Recommend scaling every arm by
    one factor, holding the ratio fixed, because the ratio is the thing being
    held constant and a search that changed it would be searching two
    variables. `rungs` then reports the factor and the arm rates it produced.
4. **Does `completing` stay run-level?** It names one step and one sink.
    Recommend leaving it on `Simulation` rather than on `Arm`: the sink belongs
    to the run, and the step name is already unique across arms by the rule
    above.
