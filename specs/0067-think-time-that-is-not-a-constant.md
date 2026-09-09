# 0067 — Think time that is not a constant

## Problem

`pause` takes a `Duration` and `Step.Pause` holds one, so users that reach a
pause together leave it together: two hundred that met `pause(2.seconds)` click
again two seconds later to the scheduler's resolution, a burst no population of
readers produces. And a scenario is a value built once, so
`pause(Random.nextDouble(4.0).seconds)` draws once and hands every user the same
constant; varying a wait per user today means sleeping inside a step body, where
it is timed as the target's latency.

This is 0034's argument one layer in. Even spacing understates queueing at the
same mean rate `[POISSON]`, which is why `randomized(seed)` exists; a constant
pause puts that metronome back inside the journey. Proofload is already partly
open — sessions arrive open, each closed between its own steps `[OPENCLOSED]` —
and it draws the open half from a seed while the closed half keeps time.

## Not doing

- No closed model. `ClosedUsers` is 0024's `spec-0024-closed` and still unbuilt;
  this is the length of a wait, whichever model it sits in.
- No distribution fitted from production data. That is traffic replay, later.
- No default seed, and nothing drawn from a source the caller did not name.
- Nothing on the timed path: the draw happens where the pause already parks,
  between steps on the user's own virtual thread.
- No row on the report for a pause. `Step.Pause` records nothing and is not
  latency; that is why it has no name, and it stays.
- No other randomness in a scenario. A weighted branch is still 0053's own spec.

## Shape

```kotlin
val checkout = scenario("checkout") {
    exec(browse, api.get("/products"))
    pause(exponential(mean = 2.seconds))        // a user reading the page
    exec(pay, api.post("/orders"))
}

val soak = checkout
    .injecting(hold(200.perSecond, over = 10.minutes).randomized(seed = 20260826))
    .thinkingFrom(seed = 20260826)
```

- `ThinkTime` — a sealed value in core: `Constant(duration)`, `Exponential(mean)`,
  `Lognormal(median, sigma)`, `Uniform(from, until)`, with `drawnFrom(random)`.
- `Step.Pause(think: ThinkTime)`. `pause(2.seconds)` still compiles and means
  `Constant(2.seconds)`: the constant is the degenerate case, not a second step.
- `Scenario.thinkTimes` — what a scenario declares, off the tree as `stepNames`.
- `Simulation.thinkingFrom(seed)` — one seed for the run, beside `fedBy`. A
  non-constant pause with no seed is refused in `init`, by the gate that already
  refuses two arms sharing a step name.
- `PlannedArm.thinking` and `.thinkSeed`, and a sentence beside the arrivals one:
  *Think time was drawn from seed 20260826: exponential, mean 2s.*

## Why this shape

A value, not a lambda. `pause { random -> ... }` is fewer lines and takes the
scenario out of what this repo can print, compare and seed: `plan()` could not
name the distribution, the page could not say which was asked for, and nothing
could tell a constant from a draw to know whether a seed is owed.

Where the seed lives, three ways. On each `pause`, which makes one scenario
reproducible in four places. On the profile's `Randomized`, nearly free but it
ties think time to Poisson arrivals, so drawn waits under an even rate line
become impossible. On the run beside `fedBy`, each arm drawing from `seed` plus
its index as 0034 seeds each stage. Recommend the third, with no default and a
`require` rather than a convention: the silent alternatives — a seed off the
clock, a fallback to the constant — measure other than what the page claims.

A user's draws are its own: the walk seeds one `Random` from the arm's seed and
the user's number, drawing in walk order, mixed as 0034 mixes a stage's seed
with a window's. User 4,001 parks the same tomorrow whatever the target did, as
`Feeder` already gives for what it sends; one shared `Random` would be
contention on the path whose delay is reported as latency.

`during` needs no change. Its clock is read between iterations and a pause runs
to completion inside one, so a drawn pause varies how many turns a user takes
rather than cutting one short — the spread the constant hides. 0053 still holds:
a user still looping when the window closes extends the run, and an unbounded
tail makes that longer.

## Stack

- [x] **`spec-0067-distribution`** — `ThinkTime`, its constructors and draw,
      `Step.Pause` carrying it, `Scenario.thinkTimes`.
      Done when: `pause(2.seconds)` builds a constant pause with every existing
      scenario unchanged, and one distribution drawn twice matches on one seed
      and differs on two.
- [x] **`spec-0067-seeded`** — `Arm.thinkSeed`, `Simulation.thinkingFrom`, the
      refusal when a distribution has no seed, the engine drawing per user where
      the pause already parks.
      Done when: two runs at one seed park the same user for the same duration,
      constant pauses still run unseeded, and a distributed pause with no seed
      fails before anything departs.
- [x] **`spec-0067-honest`** — the plan carrying the distributions and the seed,
      the line on the page and in the markdown, the cookbook's examples.
      Done when: a drawn pause names its distribution and seed beside the
      arrivals line, and constant pauses say so.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build && ./gradlew :examples:timingTests
```

## Open questions

1. **Does a drawn pause need an upper bound?** The engine waits for its last
    user, so one four-minute draw is four minutes of run. Recommend an optional
    `atMost`, printed where the distribution is, over a default cap.
2. **Which distributions, parameterised how?** Recommend the three above.
    `EVIDENCE.md` has no key for the *shape* of think time, so this spec claims
    only that it is not a constant; anyone wanting the docs to say lognormal is
    the usual model adds the source first.
3. **Does `thinkingFrom` also seed the arrivals?** Recommend no: two facts, two
    lines on the page. A caller wanting one number passes it twice.
4. **What does `Scenario.trace` draw from?** It walks one user with no
    simulation and so no seed. Recommend user 0 from seed 0, still parking — a
    trace already waits out a 30-second constant pause, so nothing changes.
