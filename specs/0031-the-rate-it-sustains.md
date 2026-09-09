# 0031 — The rate it sustains

## Problem

Every run answers "what happened at the rate I picked". Nobody picks a rate
because they want to know about that rate; they pick it because they want to
know what the target can take, and then they read the peak off a run that went
too far and write that down.

The peak is the wrong number. Response time is hyperbolic in utilisation, so a
system reaches a rate it cannot operate at, and the band between the two is
where systems collapse and stay collapsed after the load goes away
`[QUEUEING]` `[METASTABLE]`. Every serious benchmark in the field reports a
constrained maximum instead, under three names for one idea: Linear Road's
L-rating `[LINEARROAD]`, sustainable throughput `[SUSTAINABLE]`, Theodolite's
demand and capacity metrics `[THEODOLITE]`.

Proofload already has every value this needs. 0020 made goals values that judge a
result, 0014 made a shape a value, and a scenario has been a value since 0001.
What is missing is the loop.

## Not doing

- No resource dimension. Theodolite varies pods as well as load; here the
  target is whatever it is and only the rate moves.
- No hysteresis run. Ramping past the knee and back down to find the rate a
  system recovers at is the obvious next spec and not this one.
- No fitting a scalability model to the curve. The curve is data; a model on
  top of it is a separate argument.
- No new goals. The search judges the goals it is given.

## Shape

```kotlin
val search = checkout.sustainable(
    upTo = 10_000.perSecond,
    holding = 2.minutes,
    expecting = listOf(p99(pay) under 200.milliseconds, failureRate under 1.percent),
)

search.rungs                  // the rates it will try, before anything is sent
search.worstCase              // 34m, so nobody starts this by accident

val capacity = proofload.run(search)
capacity.rate                 // 4,800/s
capacity.limitedBy            // the goal that stopped it
capacity.curve                // every rung: its rate, its verdicts, its result
```

- `Search` — a value: the scenario, the goals, the ceiling, the hold. It answers
  `rungs` and `worstCase` before a request leaves, the way a profile answers
  `userCount()`.
- `Capacity` — the highest passing rate, the goal that stopped it, and the curve.
- A rung where `fellBehind()` is true is **void**, not failed. The injector could
  not offer the load, so nothing was learned about the target, and a void rung
  that ends the search would report the generator's ceiling as the target's.

## Why this shape

Coarse ladder first, then bisection between the last pass and the first fail.
Pure bisection is fewer runs but assumes the answer is monotone, which
retrograde scaling breaks, and it leaves no curve. A pure ladder gives the curve
at linear cost. The hybrid puts resolution where the knee is and spends nothing
on the flat left-hand side, which is what the queueing argument says to do
`[QUEUEING]`.

Wall clock is the real objection, and the answer is more rungs rather than
longer ones: five or fewer repetitions and five-minute runs were enough per
point in the work this borrows from `[THEODOLITE]`. `worstCase` up front is
there so the cost is a decision rather than a surprise.

## Stack

- [x] **`spec-0031-search`** ([#2](https://github.com/matthewjones372/proofload/pull/2)) — `Search` and `Capacity` as values: rungs,
      `worstCase`, and the ladder-then-bisect strategy over a supplied judge.
      Done when: the rungs of a search are answerable without running it, and a
      synthetic judge that fails above a known rate is found within one step.
- [x] **`spec-0031-run`** ([#15](https://github.com/matthewjones372/proofload/pull/15)) — running a search on the engine, void rungs, and
      `limitedBy`.
      Done when: a target that fails a p99 goal above a rate reports that rate
      and names the goal, and a run where the injector fell behind reports the
      rung void rather than as the answer.
- [x] **`spec-0031-page`** ([#7](https://github.com/matthewjones372/proofload/pull/7)) — the curve on the report, with the operating point
      marked.
      Done when: the golden shows every rung, its verdict, and which one was
      chosen.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Does a rung need a warm-up?** It does, and 0032 is the spec that finds one.
    Recommend this spec depends on it rather than inventing a second rule: until
    0032 lands, a rung holds for `holding` and judges the whole window.
2. **Does the search stop at the first failing rung?** Recommend continuing for
    a small bounded number of rungs past it. The shape past the knee is what
    tells a reader whether the target sheds or collapses, and it is cheap once
    the load is already there.
3. **Where does the ceiling come from?** Recommend requiring `upTo`, with no
    default: a search that discovers its own ceiling is a search that runs until
    something falls over, which is a different feature with a different consent.
4. **Is `holding` per rung or in total?** Recommend per rung, since it is what
    `worstCase` multiplies, and a total would silently shrink each hold as the
    ladder grows.
