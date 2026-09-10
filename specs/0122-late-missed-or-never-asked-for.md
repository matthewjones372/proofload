# 0122 — Late, missed, or never asked for

## Problem

Six things can make a run slow, and the result has words for two of them. The
engine records `behind` (lateness at mount), `latePerSecond`, `hiccups` (JVM
stalls, sampled off a platform thread) and `limits` (descriptors, ports, CPU).
From those a reader is expected to tell apart:

| What happened | Recorded where | Told apart? |
|---|---|---|
| the target was slow | `serviceTime` | yes |
| the departure left late | `behind` | yes |
| the JVM stalled | `hiccups` | yes, at 1 ms resolution |
| the injector ran out of room | `limits` | descriptors and ports only |
| **a carrier was not free mid-journey** | `serviceTime` | **no — reported as the target** |
| **an arrival was never sent** | nowhere | **no** |

The last two are the gap. A virtual thread that is descheduled inside
`action.attempt` — carriers saturated, another user pinned, a CPU-heavy body
next door — has that wait counted in service time and in response time, and
`behind` stays flat because *that* user departed on time. Little's law agrees,
because both sides inflate together. Every gate stays green. 0126 owns
detecting it; this spec owns naming it and the vocabulary the rest depend on.

The second: nothing counts arrivals. `ArrivalRecorder.record(departure.offset)`
folds the **intended** offset, so `Arrivals` describes the plan's spacing even
when the departures burst. A reader is told the arrivals were Poisson with
CoV 1.0 while the wire saw a solid block.

## Not doing

- **No throttling, no dropping and no catch-up policy change.** Today nothing
  is ever dropped: `BookingWindow` books what fell due while it was away, in
  order, and those users leave late. That is the right behaviour and this spec
  writes it down rather than replaces it.
- No change to `MATERIAL`, `lostGround` or `heldScheduleFor` (0043, 0097).
- No pre-flight ceiling. 0076's fourth open question still stands.
- No per-step lateness. A departure is late once, for the user.

## Terminology

Fixed here, once, and used by every spec after this one.

- **Requested arrival** — the offset `InjectionProfile.departures()` names for
  user *n*, computed from *n* and never from anything a response did.
- **Booked** — handed to the scheduler by the pump. Counted by `Departures`.
- **Departure** — the instant the user's virtual thread is mounted and its
  first step is about to run. What `lateness()` reads.
- **Lateness** — departure minus requested arrival, floored at zero.
- **Missed arrival** — a requested arrival that was never booked. Impossible
  today; the invariant is that it stays impossible.
- **Dropped arrival** — a booked arrival that never departs. Reachable only by
  cancellation or JVM exit (0128).
- **Catch-up** — booking two or more arrivals in one `fill` because their
  offsets both fell inside the elapsed window. Immediate and unpaced: no
  arrival is deferred to smooth a burst, and lateness is what records it.
- **Scheduler saturation** — the pump plus one task per departure exceeding
  what one platform thread can run. Shows as lateness on every departure after
  it starts, not on one.
- **Carrier starvation** — a mounted-and-descheduled user. Distinct from all of
  the above and currently invisible.

## Invariants

1. A response never determines when a future open-model arrival is scheduled.
   Structurally true: `departures()` is a pure function of the index, and no
   step runs on the scheduler thread.
2. Every requested arrival is booked exactly once, or the run did not complete.
   `booked` + `dropped` = `userCount()`, and both are on the result.
3. Lateness is never subtracted from service time and never added to it.
4. `serviceTime + lateness == responseTime`, exactly, per sample.
5. A late departure delays no other departure. The pump holds one departure and
   never sleeps; a slow `book` is itself lateness on the departures behind it,
   and must be reported rather than absorbed.
6. Recorded arrivals describe what left, not what was planned.

## Shape

```kotlin
result.arrivals.requested       // what the profile named
result.arrivals.departed        // what actually left, with its own CoV
result.arrivals.missed          // 0 — and a run that cannot say so reads null
result.schedule.saturated       // the first second lateness became monotone
```

- `ArrivalRecorder` folds a second series from the *actual* departure offset
  the walk already computes, on the same single thread, at the same cost.
- `Arrivals` grows `requested` beside `departed`; the page prints both and says
  which one the CoV belongs to. A closed run reports neither.
- A `missed` count that is structurally zero is still reported, because a zero a
  reader can see is the evidence, and a null says the run could not count.

## Adversarial cases — what the result must say

| Case | Must read |
|---|---|
| scheduler 1 ms late, once | `behind.max ≈ 1 ms`, `Valid`, one second's `latePerSecond` moved |
| 100 ms late, once | `Partial(FellBehind)` if it moves a tail; `heldScheduleFor` unchanged |
| several seconds late, once | `lostGround` true, `Invalid`, `heldScheduleFor` = the second before |
| repeatedly falling behind | lateness monotone across seconds; `saturated` names the first |
| falls behind and recovers | `heldScheduleFor` < window, later seconds flat, `offered.left` < asked |
| never recovers | as above and `offered.over` > `plannedWindow` |
| rate above what the machine can generate | `Invalid`, `offered.share` well under 1, **no arrival missing** |
| carrier starved mid-journey | today: green with an inflated `serviceTime`. 0126 |

## Testing strategy

Deterministic where it can be (0124): booking, ordering, catch-up and the
arithmetic of every row above are functions of a clock the test supplies.
Wall-clock, in the `timing` lane, for the rows that need a real scheduler: a
step body that blocks a platform thread the scheduler needs, and the recovery
after it stops.

## Stack

- [ ] **`spec-0122-vocabulary`** — the terms above written into `docs/concepts.md`
      and the KDoc that currently says "lateness" three ways.
      Done when: `behind`, `latePerSecond`, `heldScheduleFor` and `Offered` each
      name one of these terms and no other word for it survives.
- [ ] **`spec-0122-departed-arrivals`** — the second arrival series, folded from
      the actual departure offset.
      Done when: a run held up for one second reports a departed CoV above its
      requested CoV, `:benchmarks:ceiling` is unchanged, and a closed run
      reports neither.
- [ ] **`spec-0122-counts`** — `booked`, `departed` and `missed` on the result,
      with `missed` structurally zero and asserted so.
      Done when: a run of 1,000 arrivals reads 1,000 booked and 1,000 departed,
      and a run interrupted mid-flight reads fewer departed than booked.
- [ ] **`spec-0122-saturation`** — `schedule.saturated`: the first second after
      which per-second lateness p50 never returns under one interval.
      **Not recommended.** `heldScheduleFor` (0043) already answers "when did
      it stop keeping the schedule" off the same series, and a second derived
      number over the same data is one more thing to keep in step for a
      distinction nobody has asked for yet. Revisit if a real run produces a
      recover-then-fail shape the existing number reads wrong.
      Done when: the recover row names a second and the never-recover row names
      an earlier one, both from a recorded series with no clock in the test.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :examples:timingTests
```

## Open questions

1. **Is `missed` worth a field if it is structurally zero?** Recommend yes: the
   claim is the point, and 0128 makes it reachable.
2. **Should the pump detect its own saturation directly** — its `fill` taking
   longer than the window — rather than inferring it from lateness? Recommend
   inferring: a direct measurement is a second number free to disagree with the
   lateness the users actually saw.
3. **Two CoVs on one chart, or two charts?** Recommend one chart, two series;
   the whole finding is the gap between them.
