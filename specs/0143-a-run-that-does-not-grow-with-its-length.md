# 0143: A run that does not grow with its length

## Problem

A run keeps a coarse histogram pair (service time and response time) for every
second of every step, plus one lateness table a second, until it ends
(`RunRecorder`'s `Seconds`, `SecondRecorder` and `Lateness`, spec 0025). Only at
`freeze()` does each second shrink to the buckets it counted. So what a run holds
grows with how long it runs, and nothing bounds it. `docs/what-it-costs.md`
measures it: an hour of ten steps at twenty requests a second holds about 440 MiB.

That was a fair price for a load test of minutes. It is not one for a generator
left running. The petshop's demo runs Proofload as steady background traffic,
three steps for a day at a time. It ran out of heap in under two hours, after
about 74,000 requests, and the JVM died with an `OutOfMemoryError` in the
`HttpClient`'s selector thread rather than any message saying why. A class
histogram of a short run of the same scenario shows the cause: `long[]` from
6.8 MB to 27.8 MB in ninety seconds, held by `SecondRecorder` (2,174 of them
after 110 seconds) and its `Histogram`s, with nothing else growing.

A tool whose job is to run for as long as it is asked should not need its
user to know this and cut runs short. The demo now restarts every ten minutes
to stay clear of it, which is a workaround, not a fix.

## Not doing

- Dropping the timeline. Steady-state detection, stages, merging runs and the
  report all read it.
- Changing the whole-run statistics. They are full precision and fixed size
  already.
- Bounding a run's own requests in flight, or what a scenario's code holds.

## Shape

What a run holds is bounded, whatever its length, and close to what the frozen
result would hold anyway:

1. **A second is frozen when it closes.** Once no request that departed in it can
   still complete, its recorder is folded into the sparse form `freeze()`
   already produces (the buckets that counted, "tens of them a second") and
   the full tables are released. "Can still complete" is the oldest departure
   still in flight, which the engine knows. A record that lands in a second
   already closed (a body's own sample with an earlier `at`) is still counted,
   and folded in at the next close.
2. **The timeline has a fixed number of slots.** When a run passes it (say 3,600
   slots), neighbouring slots are merged in pairs and every slot's width
   doubles: one second, then two, then four. Buckets add, so merged slots are
   exact. `Second` gains `width: Duration`, and readers of the timeline
   (steady state, stages, `Concurrency`, the report's charts) read slots of
   that width rather than assuming one second.
3. **Lateness follows the same rules**: frozen when closed, merged past the
   same number of slots.

```kotlin
// the engine, once a second, off the path a request is timed on
recorder.close(before = minOf(sinceStart, oldestDepartureInFlight))

// a long run's timeline, slot by slot
result.timeline.first().width // 1.seconds for an hour; 32.seconds a slot for a day
```

With both, a day-long run of three steps holds at most 3,600 sparse slots per
step, a few megabytes, and the same as an hour-long run.

## Why this shape

Freezing closed seconds alone makes the per-second cost small but still
linear: a long enough run still runs out. A fixed number of slots alone keeps
full tables live until they are merged. Together, memory is fixed and the
report keeps a whole-run picture, at a resolution that coarsens only as the
run outgrows it. The alternative of keeping only the last N seconds (a ring)
is simpler and bounded, but it throws away the start of the run, which is
where warm-up and most schedule losses are. Recommended: freeze and widen.

## Stack

- [x] **`spec-0143-freeze-closed`**: seconds frozen into their sparse form once
      no request that departed in them is in flight; lateness too.
      Done when: `:benchmarks:timelineCost`'s recording figure for the hour of
      ten steps is within a few percent of the frozen figure, rather than the
      full tables', and the frozen result is unchanged.
- [x] **`spec-0143-widen`**: a fixed number of slots with widening by merging
      pairs, `Second.width`, and every reader of the timeline using it.
      Done when: a run of a simulated day holds the same number of slots as an
      hour; merging a widened run with one that is not gives the coarser of the
      two; steady state and stages pass their tests on widened timelines.
- [x] **`spec-0143-cost`**: `docs/what-it-costs.md` and `:benchmarks:timelineCost`
      gain the day-long run.
      Done when: the doc states what a day-long run holds, measured.

## Acceptance

```bash
./gradlew :proofload-core:test :proofload-engine:test :benchmarks:timelineCost
```

Then the petshop's demo traffic runs a day at a time again, without its
ten-minute restarts, with a flat heap.

## Open questions

- **How many slots?** 3,600 keeps an hour at one-second resolution, and a
  widened day at 32 seconds a slot. Recommended: 3,600, settable on the run.
- **What closes a second?** The oldest departure in flight is exact but needs
  the engine to report it to the recorder. A fixed lag (a second closes after the
  scenario's longest timeout) is simpler and can be wrong. Recommended: the
  oldest departure in flight.
  **Settled in `spec-0143-freeze-closed`:** the oldest departure in flight. The
  engine counts users out by the second they departed in, and the progress
  sampler closes everything before the oldest once a second. Being wrong costs
  memory, not a number: a record for a closed second is counted beside it and
  folded in at the next close.
- **Does a widened timeline change any verdict?** Steady-state detection reads
  per-second variance. Recommended: it reads variance per slot, scaled by
  width, and a test pins that a day-long steady run is judged steady.
- **What of the users in flight, sampled once a second?**
  **Settled in `spec-0143-cost`:** kept one reading a slot, the first taken in
  it, and widened with the timeline. A second each, a day's readings were
  6.2 MiB, against 47.9 MiB for the whole timeline of ten steps and 7.0 MiB for
  the demo's three. A reading is kept rather than averaged, so `Concurrency`
  still averages counts the sampler took.
