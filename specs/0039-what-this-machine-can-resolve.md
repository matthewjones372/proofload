# 0039 — What this machine can resolve

## Problem

`fellBehind()` catches the generator failing loudly. Nothing catches the machine
being quietly variable, and that is the failure that produces a confident wrong
answer rather than an obviously broken one.

0030 records the machine in a baseline and refuses to compare across a different
one, which handles "this is a different runner". It does not handle "this runner
is not steady enough to answer the question you asked". Those are different
problems: a four-core GitHub-hosted runner compared against itself still moves
by a few percent between identical runs `[CINOISE]`, and this repository's own
benchmark already shows p99 tails dominated by machine stalls rather than by
load.

A report that states a 3% improvement on a machine that cannot resolve 6% is
lying with arithmetic. The tool is in a position to know that and say it.

## Not doing

- No modelling of the noise. Measuring it is enough and modelling it invites
  arguing with the model.
- No refusing to run. The run happens; what changes is what the comparison is
  allowed to conclude.
- No cross-machine anything. 0030 owns comparability between machines.
- No continuous background monitoring. A calibration is a phase, not a daemon.

## Shape

```kotlin
val floor = kestrel.calibrate()          // a null step, a fixed schedule, repeated
floor.resolution                          // 0.061
floor.hiccups.p99                         // 14ms, what the injector itself stalled for
```

and on every report that has one:

> Calibrated on this machine: differences under **6.1%** are not resolvable
> here. The injector's own stalls reached **14 ms** at p99.

- `calibrate()` — the existing steps against a null action, no socket and no
  target, repeated enough times to give a spread. What moves is the machine and
  Kestrel, which is exactly the floor.
- A **hiccup recorder** on the injector JVM: a thread that asks to sleep a
  millisecond and records what it actually got, so a stall in the measuring
  process appears beside the tail it caused rather than inside it `[HICCUP]`.
- 0038's comparison consults the floor and returns `CannotTell` below it.

## Why this shape

The alternative is to infer the floor from the run's own variance, which is
cheaper and confounds the target's variability with the machine's: a target that
is genuinely erratic would raise its own noise floor and hide its own
regressions. A null step measures one thing.

0030's calibration probe and this are close enough to be confused, and should
not be: that one asks "is this the same machine as the baseline", this one asks
"how small a difference can this machine see at all". Recommend the two share
the probe and report separately.

The hiccup recorder is here rather than in its own spec because a resolution
line with no explanation of what moved is half a feature. It is also the cheapest
diagnostic in the tool: one thread, one histogram, no effect on the timed path.

## Stack

- [x] **`spec-0039-hiccups`** ([#16](https://github.com/matthewjones372/kestrel/pull/16)) — the recorder on the injector JVM, and its
      distribution on the result and the page.
      Done when: an injected pause in the generator shows up in the hiccup
      distribution and is visible beside the response times of that window.
- [x] **`spec-0039-calibrate`** ([#23](https://github.com/matthewjones372/kestrel/pull/23)) — the null-step calibration and `resolution`.
      Done when: repeated calibration on one machine reports a stable floor,
      repeats that landed further apart report a larger one, and the number
      lands on the report.

      The done-when originally said "a machine with an artificial background
      load reports a larger one", and that is not reproducible: CPU-bound
      spinners at twice the core count do not move the measurement at all, and
      the loaded reading can come back lower than the idle one. What moves it is
      a neighbour doing scheduling work — a competing run in the same JVM took
      it from 0.007-0.046 to 0.132-0.218 — but the idle reading spikes often
      enough on a shared machine that a one-sided gate would flake. The
      mechanism is asserted deterministically instead, and the magnitude is left
      unasserted.
- [x] **`spec-0039-consulted`** ([#37](https://github.com/matthewjones372/kestrel/pull/37)) — 0038's comparison returns `CannotTell` below
      the floor, naming it.
      Done when: a 2% difference on a machine with a 6% floor reports cannot
      tell rather than better.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **How long does calibration take?** Recommend a bounded thirty seconds, run
    once per JVM and cached, with an opt-out for people who have measured their
    machine already.
2. **Is the floor absolute or relative?** Relative, expressed as a percentage of
    the statistic, since that is the form a comparison needs — but relative *at
    the median*. Measured at p99 a null step's floor is 140% to 2400% and swings
    by twentyfold between calibrations, because a null step's p99 is the
    machine's stalls sitting on a base of a few milliseconds; that is honest and
    useless, since no comparison could ever pass it. So `resolution` bounds a
    median-scale comparison and a tail comparison must additionally clear
    `hiccups.p99` in absolute terms. Two gates, because there are two questions:
    how big is the change, and is it bigger than what this machine's own stalls
    can produce.
3. **Does the floor belong in the baseline file?** Recommend yes: a baseline
    that carries the floor of the machine that produced it lets a later run say
    which of the two machines was the limiting one.
4. **What if the floor is enormous?** A runner with a 40% floor cannot support
    any latency claim. Recommend saying that at the top of the page in place of
    the comparison, rather than printing a comparison nobody should read.
