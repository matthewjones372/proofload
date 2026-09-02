# 0072 — Replaying real arrivals

## Problem

0034 stopped the metronome by drawing arrivals from a Poisson process, and drew
a line in its **Not doing**: "No traffic replay, no arrival profile imported
from production. Same." This spec is that line moved.

Poisson at the mean rate is the right correction to even spacing and the wrong
description of a busy hour. Session arrivals are close to Poisson, arrivals
below that level are not, and the burstiness does not average out `[POISSON]`:
real traffic is diurnal and correlated — a flash crowd, a retry storm, a batch
job on the hour — so a run that is Poisson at the mean rate still understates
queueing. The team that hits this holds the answer in a file, an access log or
an OTel export, and can only read the mean rate off it and hand `hold` a number.

## Not doing

- **No capturing.** The user's file is the input; this is a reader.
- **No replaying bodies or paths.** The scenario supplies the requests; this
  replays *when*, not *what*. Replaying what needs every request recorded and
  correlated, and ships production personal data into a test environment.
- **No parametric fit.** No Hurst parameter, no Weibull, no MMPP: the series is
  the model, and a fit prints a number the capture did not contain.
- **No wall clock.** Instants become offsets from the run's start: the shape
  travels, the time of day does not.
- **No resampling.** Only the window arithmetic may move an arrival.
- **No change to the default,** per 0034's third bullet and for its reason.

## Shape

```kotlin
val friday = arrivalsFrom(csv(Path.of("friday-peak.csv")), column = "at")
friday.count       // 1,204,663, before anything is sent
friday.span        // 1h
friday.cov         // 2.71 — how bursty that hour actually was
val peak = friday.replaying(from = 40.minutes, window = 10.minutes, scaled = 2.0)
peak.userCount()   // 214,880, exact, before the run
peak.over          // 5m — ten captured minutes at twice the rate
```

- `ArrivalSeries` is a capture read once and held as a value, with an overload
  of `arrivalsFrom` for instants parsed elsewhere, and `InjectionProfile.Replay`
  a fifth variant holding the series with the window and scale taken out of it.
- On the page, where 0034 prints seeds: *Arrivals replayed from friday-peak.csv,
  scaled ×2.0, 10m from 40m in. Capture CoV 2.71; measured 1.396ms apart, 2.71.*

## Why this shape

A variant, not a `Sequence<Duration>` handed to the engine. Ten `when`s over
`InjectionProfile` — seven in core, two in `PlanView`, one in the baseline format
— carry no `else`, so the compiler names every question a shape must answer
before anything is sent: `userCount`, `over`, `departures`, `endRate`, the page's
words and chart, the baseline's line. A sequence answers `departures()` alone and
leaves plan, report and `Runs`'s unlike-plan refusal blank. `then` takes a replay
as a stage, unopened by `asStages` as a `Randomized` is.

Scaling is time-scaling, not thinning. Keeping the window and dropping arrivals
with probability *p* is wrong in a way worth naming: independent thinning drives
any point process towards Poisson — the shape being replayed away — and doubling
by duplication invents arrivals. Multiplying every gap by 1/k leaves the
coefficient of variation exactly where it was, which makes the page's two numbers
comparable at any scale; the run's length moves instead, so it is printed. And it
is reproducible by construction: no seed, nothing to record, where 0034 draws.

Offsets are a `LongArray` of nanoseconds — a million is eight megabytes, where a
`List<Duration>` boxes each — and `departures()` is a lazy map over a slice, so
0028's `BookingWindow` still holds five seconds of tasks whatever the capture's
size; per 0054 that arithmetic belongs measured in `docs/what-it-costs.md`. It is
also why equality follows `CsvFile`: `Runs` prints a profile into its refusal and
0021's baseline carries one, so a series compares by source, count, span, digest.

## Stack

- [x] **`spec-0072-capture`** — `ArrivalSeries`, `arrivalsFrom` over a column
      and over instants, and `count`, `span`, `cov`.
      Done when: ISO-8601 instants read back as a series whose figures are
      values before anything departs, an unordered column is sorted rather than
      refused, and fewer than two instants is refused by name.
- [ ] **`spec-0072-replay`** — `InjectionProfile.Replay`, `replaying(from,
      window, scaled)`, and the ten `when`s that now answer for it.
      Done when: at ×1 departures match the capture's gaps nanosecond for
      nanosecond, at ×2 every gap and `over` halve, `userCount()` answers first,
      a replay `then` a hold is one shape, and `randomized` fails by name.
- [ ] **`spec-0072-page`** — the arrivals line naming source, scale and window,
      both coefficients of variation, and a shape chart drawn per second from
      the capture rather than a rate line.
      Done when: a replayed run never prints "evenly spaced" and its two
      coefficients agree within a tolerance at ×1 and ×2.
- [ ] **`spec-0072-baseline`** — the replay's line in the baseline format, and
      the identity `Runs` compares on.
      Done when: a run read back carries a profile equal to the one that ran
      without the file holding a timestamp, and two captures are refused by name.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Can a replay be randomised?** Recommend no, refused by name in
    `randomized`'s new branch: drawing over a replay throws away the shape it
    was imported for, a broken assumption rather than a declared error.
2. **What is a replay's `endRate`, which `thenRampTo` reads?** Recommend the
    mean rate over the replayed window. Its last second's rate is drawn from the
    burstiness, so one capture would hand two ramps different starts.
3. **Do `from` and `window` measure the capture or the run?** Recommend the
    capture, `over` falling out of the scale; the cost is dividing to get a
    five-minute run at ×2, and the page prints both.
4. **Should Kestrel offer a synthetic bursty model** — Poisson with an on/off
    overlay, or an MMPP — for a team with no capture? A later spec, and only if
    the parameters can be cited: numbers somebody chose look like production.
5. **Does `EVIDENCE.md` gain a key?** `[POISSON]` carries "does not average out",
    which is what the Problem leans on; the heavy-tailed and self-similar claim
    rests on a literature this repository has not cited yet.
6. **What does the comparison print for a mix?** Two arms replaying two captures
    make a third process nobody captured. Recommend comparing only where a
    single arm replays, and naming each arm's source otherwise.
