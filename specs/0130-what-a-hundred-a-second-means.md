# 0130 — What a hundred a second means

## Problem

`rate(100.perSecond)` has exact semantics and they live in three private
functions in `InjectionProfile.kt`. `userCount()` truncates; `atRate` divides an
index; `drawnAcross` conditions a Poisson process on a one-second window;
`ownedBy` filters a merged sequence. Each is right and none is written down
anywhere a user, a port, or a second implementation could read.

That matters more than it would for most features, because the arrival schedule
is the thing every other claim rests on. If two injectors disagree about which
users they own, the merged run is missing arrivals nobody counted. If a stage
boundary is inclusive at one end and exclusive at the other, `hold(r, 1.s) then
hold(r, 1.s)` sends a different number of users from `hold(r, 2.s)`, and the
per-stage goals of 0084 are judged over windows that do not tile. If truncation
is not stated, `1.perSecond over 500.milliseconds` sends **zero** users and
reports a green run.

There is no specification of this today: 0004 describes the engine, 0014 the
shapes, 0034 the randomisation, 0070 the sharding, and none of them states the
arithmetic as a contract.

## Not doing

- **No change to any of the arithmetic.** Everything below is what the code
  does. Where the current answer is arguably wrong — the zero-user run — the fix
  is a refusal at construction, not different rounding.
- No new profile shape and no `rateAt(offset)`. 0076's second open question
  still stands.
- No closed-model semantics. 0131.
- No pacing or smoothing. 0122 settles that nothing is deferred.

## The semantics

For a profile *P* over window *W*:

**1. Arrivals are a pure function of the profile, computed before execution.**
`departures()` is a lazy `Sequence<Duration>` whose *i*-th element depends only
on *i* and on *P*. No response, no measurement and no clock reading enters it.
This is the invariant everything else in the repository rests on.

**2. Count.** `N = floor(area under the rate line over W)`.

- `ConstantRate(r, W)`: `N = floor(r × W)`.
- `RampRate(a, b, W)`: `N = floor(a·W + (b−a)·W/2)`.
- `Stages`: the sum of the stages' counts, each computed on its own window.
- `Randomized`: unchanged from what it wraps.
- `Replay`: the number of captured arrivals inside the window asked for.

**3. Offsets.** For a constant rate, `t(i) = i / r`, computed from *i* — never
accumulated, because a floating-point interval added N times drifts and a
generator that drifts reports the drift as latency. For a ramp, `t(i)` is the
positive root of `a·t + c·t²/2 = i` where `c = (b−a)/W`; where `a == b` the
constant form is used and nothing is divided by zero.

**4. Boundaries are half-open: `[0, W)`.** `t(N−1) = (N−1)/r < W` always, and an
arrival exactly at `W` belongs to the next stage, not this one. This is what
makes stages tile: `hold(r,1s) then hold(r,1s)` and `hold(r,2s)` send the same
users at the same offsets.

**5. Fractional rates and fractional users.** `0.5.perSecond over 10.seconds` is
5 arrivals at 0, 2, 4, 6, 8 s. `3.perSecond over 10.5.seconds` is 31, not 32:
the trailing half-arrival is dropped. `100.4.perSecond over 10.seconds` is
1,004. **A profile whose count is zero is refused at construction** — it is
always a mistake, and today it is a green run that sent nothing.

**6. Randomised arrivals.** A Poisson process conditioned on *N* arrivals in a
window places them where *N* sorted uniforms fall. Drawn one second at a time,
each second handed exactly the count the rate line owes it — so the total is
still *N* exactly, every arrival stays inside its own second, and a ramp still
ramps. The seed is mixed per stage (`seed + index`) and per window
(`seed + window × 2654435761`), so the whole schedule is a pure function of one
seed on any machine.

**7. Several scenarios.** Arms are **merged**, not concatenated: the earliest
departure across arms is handed out next, and arms due at the same offset leave
in the order the mix declares them. Each arm numbers its own users from zero, so
an arm's data is reproducible whatever the arms beside it are sending.

**8. Sharding.** Injector *k* of *N* walks the whole merged schedule and sends
the departures it owns, per arm. The union over all *k* is every arrival exactly
once at exactly the offsets one JVM would have used; the intersection of any two
is empty. Shard *k* is judged against `plannedInterval × N`, because it sends
every *N*-th user.

**9. Rounding, stated once.** Counts truncate. Offsets truncate to whole
nanoseconds. Percentiles round *up*, to the top of the bucket a sample fell in.
Nothing anywhere interpolates.

## Examples

```kotlin
hold(100.perSecond, over = 10.seconds)   // 1,000 arrivals, 0 ms … 9,990 ms
hold(1.perSecond,   over = 500.ms)       // 0 arrivals — refused
hold(0.5.perSecond, over = 10.seconds)   // 5 arrivals: 0, 2, 4, 6, 8 s
hold(3.perSecond,   over = 10.5.seconds) // 31 arrivals, last at 10.0 s
rampRate(0.perSecond, 100.perSecond, over = 10.seconds)   // 500 arrivals
hold(r, 1.s) then hold(r, 1.s)           // == hold(r, 2.s), arrival for arrival
```

## Acceptance criteria

Each is a property, and 0124 says they are tested without a clock.

- For every shape: `departures().count() == userCount()`.
- For every shape: every offset in `[0, over)`, non-decreasing.
- Stage tiling: the two chains above are equal as sequences.
- Determinism: the same profile yields byte-identical offsets across JVMs and
  runs, randomised or not.
- Sharding: union is identity, pairwise intersection empty, for N in 1..7.
- A zero-count profile is refused with a message naming the rate and the window.
- Scaling a `Replay` by *s* divides every gap by *s* exactly, leaving its
  coefficient of variation unchanged.

## Stack

- [ ] **`spec-0130-contract`** — the semantics above as `docs/open-model.md`,
      linked from `docs/concepts.md` and `llms.txt`.
      Done when: every clause has either a test citing it or a line saying it is
      untested.
- [ ] **`spec-0130-properties`** — the acceptance criteria as core tests.
      Done when: each bullet is an assertion and the stage-tiling one passes.
- [ ] **`spec-0130-empty`** — a zero-count profile refused at construction.
      Done when: `hold(1.perSecond, over = 500.milliseconds)` throws naming both
      numbers, and `hold(0.perSecond, over = 10.seconds)` — a deliberate silence
      in a mix — still builds.

## Open questions

1. **Should a zero-count profile throw, or produce an `Invalid` run?**
   Recommend throwing, except for an explicit zero rate. A run is minutes; a
   constructor is free.
2. **Is truncation right, or should counts round to nearest?** Recommend
   truncation and stating it: rounding up makes `hold` for half a second send an
   arrival the rate line never owed, and breaks stage tiling.
3. **Should a `Replay` state its own boundary rule?** Its arrivals are data, not
   a rate line. Recommend saying `[from, from+window)` explicitly, since the
   filter is `>= begins && < ends` and nothing says so.
4. **Does sharding divide unevenly?** With `userCount` not divisible by N, some
   shards send one more. Recommend stating it and making `Offered.asked` account
   for it rather than dividing by N.
