# 0064 — How long this will take

## Problem

A run says where it has got to and never says where it is going:

```
kestrel: 00:35  departed 1,750  in flight 12  behind 104.499us
```

A reader watching that cannot tell a thirty-second smoke test from a four-hour
soak, so the only way to know whether to wait is to go and read the profile in
the source. 0057 built the line on the argument that "a ten-minute soak is not
ten minutes of silence somebody kills"; a line that never says ten minutes only
half answers it.

A capacity search is worse. It runs a rung at a time, each rung a full hold, and
a watcher sees rung after rung with no idea how many are left — and this is the
case where somebody genuinely cannot work it out, because the ladder stops when
a goal misses and then bisects.

The thing that makes this awkward is that a finish time is a *prediction*, and
`AGENTS.md` says a number in a report is a measurement or it is a lie.

## Not doing

- **No forecast from measurement.** None is needed and none would be honest.
  A run's length is `InjectionProfile.over` — the profile already says it, in
  the value, before a request leaves — and a search's is `Search.worstCase`,
  which this repository already computes and documents as "an upper bound
  rather than a forecast". There is nothing here to estimate from a warm-up,
  and a number extrapolated from the first few seconds would be exactly the
  invented figure the rule exists to stop.
- **No prediction of the tail.** A run waits out the users it started, and how
  long they take is the target's business, not the schedule's. So what is
  printed is what was *scheduled*, and it is named that way.
- No new measurement on the timed path. A snapshot is still numbers the
  scheduler already keeps.
- No progress bar, no terminal control codes. The line is one `println` that
  survives being piped into a CI log.

## Shape

A run says its shape before it departs, and counts down while it runs:

```
kestrel: checkout — 30,000 users over 10m at 50/s
kestrel: 00:05  departed 250  in flight 3  behind 88.033us  9m55s left
kestrel: 00:10  departed 500  in flight 4  behind 91.621us  9m50s left
kestrel: 10:00  departed 30,000  in flight 41  behind 96.718us  draining
```

`left` is the schedule's arithmetic, not a forecast; once the schedule is spent
the line says `draining`, because what is left then is the target's and nobody
here knows it.

A search says its bound, and narrows it as rungs are ruled out:

```
kestrel: capacity — at most 15 rungs of 2m, so at most 30m
kestrel: rung 1 of at most 15 — 50/s
kestrel: rung 4 of at most 15 — 200/s  passed
kestrel: rung 6 — 300/s  failed, bisecting: at most 5 more, 10m
```

## Why this shape

Everything printed is already a value this repository computes and can be read
before anything is sent: `plan.plannedUsers`, `plan.plannedWindow`,
`Search.worstCase`, `Search.rungs`. So this is a reporting spec, not a
measurement one — which is why it can be honest at all.

"At most" rather than a point estimate, everywhere it is not exact. A search's
ladder stops as soon as it has the knee, so the bound is nearly always
pessimistic — and a bound that is beaten is a reader pleasantly surprised,
where a forecast that is missed is a tool that lied.

`draining` rather than a negative countdown, for the same reason. The schedule
knows when it stops asking for departures; it does not know when the last
response lands, and printing a zero would claim it did.

The alternative is to leave this to the caller, who has the `Simulation` and
could print it themselves. That is true and it is what everybody would do,
badly and differently, for a fact the tool already holds.

## Stack

- [ ] **`spec-0064-scheduled`** — a run naming its shape before it departs and
      carrying the schedule's remaining time on each tick.
      Done when: a run of a known profile prints its user count and window
      before the first departure; `left` counts down to the window and then
      reads `draining`; and `Progress.silent` still prints nothing at all.
- [ ] **`spec-0064-rungs`** — a search naming its bound and narrowing it.
      Done when: a search prints `worstCase` before its first rung, numbers
      each rung against the ladder, and says how much is left once bisection
      has begun.
- [ ] **`spec-0064-page`** — the scheduled window beside the measured one on
      the report, so a run that was cut short says so.
      Done when: a page whose measured window is shorter than its planned one
      names both rather than only what it measured.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :examples:timingTests
```

## Open questions

1. **Where does the schedule reach the reporter?** `Snapshot` is documented as
    numbers the scheduler already keeps, and the window is one of them.
    Recommend a field on `Snapshot` rather than a second method on `Progress`,
    which would stop it being a `fun interface`.
2. **Does the opening line belong to `Progress` or to the engine?** A caller
    who asked for silence should get it. Recommend `Progress`, with the
    engine handing it the plan, so `Progress.silent` swallows this too.
3. **What does a search print for a rung that voided?** The ladder stops there,
    so the bound collapses to nothing rather than narrowing. Recommend saying
    the search stopped and why, since a void rung is the generator's ceiling
    and the reader needs to know the answer is about the machine.
4. **Should the countdown appear for a profile with no window?** Every profile
    has one. A result built from samples has `Plan.none`, which is not a run.
    Recommend printing the shape only where a profile named one.
