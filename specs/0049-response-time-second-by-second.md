# 0049 — Response time, second by second

## Problem

0025's timeline keeps service time and nothing else. 0032 then restricts a
result to its steady segment by rebuilding it from that timeline — so
`result.steady` can narrow a service-time percentile and cannot narrow a
response-time one. `steady`'s response times come back empty, and a
response-time goal is judged over the whole run.

`ResponseTime` is the **default** clock for every percentile goal, and 0020
made it the default deliberately: a goal written against service time can be
met by a generator that never sent the load. So the common case — `p99(pay)
under 200.milliseconds` — is still judged over the cold start that 0032 exists
to exclude. The feature works, and it works for the clock most people are not
using.

It is stated honestly today, in the KDoc, the README and on the page. That is
the right thing to do with a limitation and it is not a reason to leave it.

## Not doing

- No change to what a steady segment is, or how it is found. 0032 settled that.
- No response time in the baseline file. 0025 settled that the format carries no
  timeline at all.
- No third clock, and no per-step steady state.
- No raising the coarse histogram's precision. 0025 argued that at 6.25% and
  0047 is making it travel with the value.

## Shape

```kotlin
result.steady[pay].responseTime.p99      // narrowed, like serviceTime already is
result.timeline.first().responseTime     // the second's response times
```

A `Second` gains a response-time distribution beside the service-time one it
already carries, and `steady` restricts both.

## Why this shape

The cost is the whole argument, and it is memory. 0025 chose a coarse histogram
precisely because a full one per second per step is seventy-seven megabytes for
a ten-minute three-step run; the coarse table is 5,384 bytes, and 0032 already
split each second into an ok and a failed table. Adding response time doubles
whatever that has become.

Three ways to pay for it. Carrying a fourth and fifth table per second is the
straightforward one and is a real multiplier on a long soak. Carrying response
time **instead** of service time is cheaper and wrong — service time is what a
reader diagnoses a target with, and the timeline is where they look. Deriving
one from the other is impossible: the scheduling delay is per request, not per
second, and a second's mean delay cannot reconstruct a percentile.

Recommend paying for it, and measuring the result rather than estimating it —
`docs/what-it-costs.md` already carries this accounting and should be the place
the decision is checked. If the measured figure turns out to be indefensible on
a long soak, the fallback worth arguing is a timeline that keeps response time
only while a response-time goal exists to need it, which the plan knows before
the run starts.

## Stack

- [ ] **`spec-0049-response`** — response time on `Second`, recorded, merged and
      restricted.
      Done when: a run with a slow first twenty seconds reports a steady
      response-time p99 that excludes them, `Runs.merged` superimposes both
      distributions, and the measured memory cost is in `docs/what-it-costs.md`.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Does the page draw a response-time line too?** Recommend not by default.
    The two lines are within the injector's backlog of each other on a healthy
    run, and a chart with two nearly-coincident lines teaches nobody anything.
    Draw it where `fellBehind()` is true, which is where they diverge.
2. **Does this want the ok/failed split as well?** 0032 split each second by
    outcome because a `StepStats` is built from `Outcome`s. Response time needs
    the same split for the same reason, which is what makes this four tables per
    second rather than three. Recommend accepting that rather than building a
    `Second` whose two halves disagree about what they carry.
3. **Is a soak the case that breaks this?** A one-hour ten-step run is 36,000
    seconds of tables. Recommend measuring exactly that before shipping, and
    recording the number rather than a reassurance.
