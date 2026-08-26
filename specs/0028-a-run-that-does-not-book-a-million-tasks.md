# 0028 — A run that does not book a million tasks

## Problem

The engine books every departure before the run starts. A ten-minute soak at a
thousand a second is six hundred thousand scheduled tasks sitting on a
`ScheduledExecutorService`'s queue, each holding a closure, from the first
millisecond.

At the rates this has been tested at it works, and the benchmark says the
median departure is within tens of microseconds. It is still a design that puts
the whole run in memory before sending a request, and the failure mode is a
cliff rather than a slope: the run that is too big does not degrade, it dies.

The comment noting this was written when the drift bug was fixed and the trade
was deliberately left in place.

## Not doing

- No change to the departure schedule itself. Offsets stay a pure function of
  the profile, computed from an index.
- No change to the recording path, which the benchmark says is fine.
- No adaptive pacing. Booking less is not the same as sending less.

## Shape

Nothing in the API changes. The engine books a window ahead of itself instead
of the whole run:

```kotlin
// internal to kestrel-engine
private const val BOOKING_WINDOW = 5.seconds
```

- The scheduler holds at most a window's worth of departures.
- A pump task tops the queue up, and is itself scheduled rather than looping.
- The benchmark grows a row for a run long enough that the difference shows.

## Why this shape

A window is the smallest change that removes the cliff. The offsets are already
a lazy `Sequence`, so pulling from it in chunks needs no new arithmetic and
cannot introduce drift: each departure is still computed from its own index.

The pump must not be a loop with a sleep. A thread that wakes, tops up and
sleeps again is a thread parked in library code, and this repository has a rule
about that for the reason this whole design exists.

## Stack

- [ ] **`spec-0028-window`** — booking a window, and the pump that refills it.
      Done when: a run of a million departures never has more than a window's
      worth scheduled, and its lateness matches the current engine's at the
      rates the benchmark already covers.
- [ ] **`spec-0028-benchmark`** — a long-run row in the ceiling harness.
      Done when: a ten-minute-equivalent run reports its lateness and its peak
      queue depth, and `docs/what-it-costs.md` carries both.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :benchmarks:ceiling
```

## Open questions

Answered by the architect:

1. **Five seconds of departures**, not a count. A window measured in time is
    the same amount of slack at every rate; a window measured in tasks is
    minutes at one rate and milliseconds at another.
2. **A run whose window cannot be refilled in time reports it as lateness**,
    through the existing `behind` series. There is no new failure mode to
    invent — falling behind is already a thing this tool measures about itself.
3. **The pump is the scheduler thread's work**, not a second thread. Two
    threads deciding when to send is two chances to disagree.
