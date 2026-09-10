# 0124 — A schedule tested without a clock

## Problem

Everything the scheduler claims is currently proven by running it and looking at
a distribution. `ScheduleDriftTest` sends 2,000/s for two seconds and asserts
`behind.p50 < 25.milliseconds`; `SchedulerTest` starts ten users and asserts
they overlap. Those are the right tests for the claims they make and they are
the *only* tests, so the claims that are pure arithmetic — how many arrivals a
profile names, where they fall, what a booking window hands over and in what
order, what lateness a given set of departures produces — are proven at a
resolution of "25 ms on a shared build machine".

Three consequences. The half-open boundary at `over` is asserted nowhere.
Catch-up is emergent: `BookingWindow` books what fell due while it was away, and
nothing states how many or in what order. And a regression in the arithmetic
shows up as a flaky wall-clock test, which is the failure mode that gets a test
tagged `timing` and then ignored.

The separation is already most of the way there and was reached for other
reasons. `InjectionProfile.departures()` is a pure sequence. `BookingWindow.fill`
takes elapsed time as a **parameter**. `RunRecorder.record` takes `at` as a
parameter — its own KDoc says "the timeline is then a function of what a caller
recorded, and a test of it needs no elapsed time". What is missing is not an
abstraction; it is tests that use the seams that exist.

## Not doing

- **No virtual clock, no `Clock` interface, no injectable time source.** The
  three seams above already make the pure part testable, and AGENTS.md is
  explicit that an abstraction added only for testability is not earned.
  `System.nanoTime()` stays where it is.
- No change to `Pump`, `BookingWindow`, `Departures` or the schedule merge.
- No removal of the wall-clock tests. They prove the half this cannot.

## Where the line falls

**Deterministic — a function of values, tested with no elapsed time:**

- arrival count for every profile shape, including truncation and the boundary
- arrival offsets, including a ramp's quadratic and a randomised draw's seed
- the merge across arms, and the tie-break when two arms are due together
- shard ownership: the union over *N* shards is every user exactly once
- `BookingWindow.fill` — what it books for a given elapsed value, what it
  returns, and what it books when called late
- lateness arithmetic, `latePerSecond`, `heldScheduleFor`, `Offered`,
  `fellBehind`, `lostGround`, `concurrency`, and every percentile over them
- ordering: departures are handed out in non-decreasing offset order

**Integration, against real time, in the `timing` lane:**

- that a departure is not delayed by a response — needs a real target
- that lateness stays bounded at a rate — the ceiling question, 0011's
- that a virtual thread is what a user runs on
- that the schedule is kept while a step blocks
- everything in 0123

## Shape

A test-only driver in `proofload-engine`'s test sources, holding a list of
elapsed instants and driving `BookingWindow` through them:

```kotlin
val booked = drive(
    schedule = 100.perSecond.over(1.seconds).departures(),
    window = 5.seconds,
    fills = listOf(0.seconds, 10.milliseconds, 3.seconds),   // a fill that arrived late
)

booked.at(3.seconds).size shouldBe 100      // catch-up books all of it, at once
booked.offsets shouldBe booked.offsets.sorted()
```

- No new production type. `drive` is thirty lines over the existing `fill`.
- Lateness is then computed off the same list rather than measured: given
  requested offsets and a departure instant per user, the whole of `behind`,
  `latePerSecond` and `heldScheduleFor` is arithmetic a test can state exactly.

## Invariants the deterministic tests establish

1. `departures().count() == userCount()` for every shape, every time.
2. Every offset is in `[0, over)`. An arrival exactly at `over` is excluded.
3. Offsets are non-decreasing, and the merge preserves it across arms.
4. `fill` books every departure whose offset is at or below elapsed + window,
   in order, and never books one twice or skips one.
5. A late `fill` books the whole backlog in one call and paces nothing.
6. The union of `ownedBy(shard k)` over k in 0..N-1 is the whole schedule, and
   the intersection of any two is empty.
7. `randomized(seed)` is a pure function of the seed: same seed, same offsets,
   on any machine.

## Adversarial cases

- `1.perSecond over 500.milliseconds` — **zero** arrivals. A run that sends
  nothing, and today reports a green failure-rate goal (0121).
- `0.4.perSecond over 10.seconds` — four arrivals, not "0.4 a second for ten
  seconds" rounded up to five.
- `3.perSecond over 10.5.seconds` — 31, not 31.5 and not 32. The half arrival
  at the end is dropped, and nothing currently says so.
- A ramp `from == to` — the acceleration is zero and the quadratic must not be
  divided by it.
- A fill that arrives a whole window late at 50,000/s — 250,000 departures
  booked in one call, all late, none lost.
- Two arms both due at offset zero — the mix's declaration order decides, so a
  run is a function of the value rather than of which iterator answered first.

## Stack

- [x] **`spec-0124-arrivals`** — invariants 1, 2, 3 and 7 as core tests over
      every `InjectionProfile` shape.
      Done when: each adversarial arrival-count case above is an assertion, and
      the boundary at `over` is stated as a test rather than as a comment.
      #89. `ScheduleArithmeticTest` in core: the counting table, the half-open
      boundary, and the seed's purity, over eleven shapes.
- [x] **`spec-0124-booking`** — the `drive` helper and invariants 4 and 5.
      Done when: the late-fill case books its whole backlog in one call, in
      order, with nothing lost, and no test in the file reads a clock.
      #89. `ScheduleDeterminismTest` in engine, with `drive` over the existing
      `fill`. The 50,000-a-second late fill books 249,999 in one call.
- [x] **`spec-0124-shards`** — invariant 6, over 1, 2, 3 and 7 shards.
      Done when: the union is the identity for every N and the merge of the
      shards' results equals the single-injector run's counts.
      #89. `ShardOwnershipTest` over 1, 2, 3 and 7 injectors.
- [x] **`spec-0124-lateness`** — lateness, `latePerSecond`, `heldScheduleFor`
      and `Offered` from stated departure instants.
      Done when: 0122's adversarial table is a table-driven test with no
      elapsed time in it, and the wall-clock tests keep only the claims that
      need a real scheduler.
      #93. `LatenessTableTest`, seven rows, no clock. Two departures from the
      spec. The validity column is absent: `Valid`, `Partial` and `Invalid` are
      0121's and 0121 is unbuilt. And the wall-clock tests did not shrink —
      both of `ScheduleDriftTest`'s measure lateness a real scheduler produced,
      which nothing deterministic can, so neither was made redundant. Weakening
      a gate to tick this box would have been the wrong trade.

## Acceptance

```bash
./gradlew :proofload-core:test :proofload-engine:test
./gradlew build
```

## Open questions

1. **Is `drive` reaching into `internal` API a problem?** `BookingWindow` is
   `internal` and the tests are in the same module, so no. Recommend keeping it
   internal rather than widening the published surface for a test.
2. **Should the boundary be half-open?** It already is, by construction.
   Recommend documenting it as a contract in 0130 rather than changing it — a
   closed boundary would make `hold(r, 1.s) then hold(r, 1.s)` send one more
   user than `hold(r, 2.s)`.
3. **Do the wall-clock tests shrink?** Recommend yes, and say so in the commit:
   a timing test that is also proven deterministically is a timing test that can
   be made less sensitive without losing the claim.
