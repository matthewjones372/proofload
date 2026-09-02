# 0065 — The injector's own limits

## Problem

`docs/what-it-costs.md` publishes a row it cannot explain: at ten thousand a
second, 29,568 failures of 50,000 requests — `IOException 17,058; timeout
12,510` — under an admission that "whether that is the client, the loopback
stack or the server's accept path is not something this sweep separates". All
are counted against the target, since `Transport.kt:40-62` turns an
`IOException` into `Threw("IOException")` and a timeout into `TimedOut`, beside
a 503 the target really sent. The likelier reading: the injector ran out of
descriptors or ports — 50,000 connections at one address, a 28,232-port range.

0039's hiccup thread stops at the JVM's scheduling. Nothing measures
descriptors, ports or CPU share — the limits that make a high rate lie.

## Not doing

- **No tuning the machine.** No `ulimit`, no `ip_local_port_range`. The tool
  says what it hit; changing it is the operator's, and theirs to record.
- **No raising a limit automatically.** A run that grows its own fd limit
  half-way through has changed what it is measuring.
- **No monitoring of the target.** Its descriptors and accept queue are
  observability. This measures this process, about this process.
- **Nothing on the departure or request path.** No counter per request, no
  syscall in `exchange`: a sampler that costs a departure is worse than none.
- **No second calibration phase.** This samples a run happening anyway.

## Shape

A sampler started and stopped where `watchForHiccups` is
(`VirtualThreads.kt:63,128`), its peaks on the result:

```kotlin
result.limits.openFiles   // Headroom.Measured(peak = 61_120, limit = 65_536)
result.limits.ports       // Headroom.Measured(peak = 27_998, limit = 28_232)
result.limits.cpu         // Headroom.Measured(391, 400), hundredths of a core
result.ranOutOfRoom()     // true: something came within TIGHT of its limit
```

`Headroom` is `Measured(peak, limit)` or `Absent(because)`, as `Tail` and `Met`
are (`RunResult.kt:17-36`). Three sources, each degrading alone: **descriptors**
from `UnixOperatingSystemMXBean`, an `is` check on the bean `ManagementFactory`
returns; **ports** from `ip_local_port_range` and `/proc/net/sockstat`, whose
`tw` is the TIME_WAIT that exhausts the range, Linux only; **CPU** from
`getProcessCpuLoad` on the same bean `Ceiling.kt:215` already reads the load
average from. Where a platform exposes none, all three read `Absent` and the
report says not measured.

Two consumers: a warning beside `behindLines`, because the counts under it are
about the wrong end of the wire; and a `Void` rung beside `lostGround()`.

## Why this shape

Inferring it from the failures instead is a guess wearing a measurement's
clothes (0062); a peak against the real limit implicates the injector or clears
it.

Peaks, not a distribution: a stall's size is a shape, which is why `hiccups` is
a `Timing`, but a limit approached is a maximum. And `Absent(because)` is not
politeness — a Windows runner reporting zero open files would be a lie in the
exact shape this spec exists to stop.

Values in core beside `Machine`, sampler in the engine beside `Hiccups.kt`, and
`ranOutOfRoom()` beside `fellBehind()`, so three sinks share one threshold.

## Stack

- [x] **`spec-0065-headroom`** — `Headroom`, `Limits`, `RunResult.limits` and
      `ranOutOfRoom()` in core, nothing measuring them yet.
      Done when: a default result reads false, a reading at 91% of its limit
      reads true and one at 40% false, and `TIGHT` is stated in one place.
- [ ] **`spec-0065-sampler`** — the sampler in the engine, on the hiccup
      thread's pattern, and the three sources.
      Done when: a Linux run's peak descriptor count is above zero and under
      that JVM's `ulimit -n`, a missing source reads `Absent` with a reason
      rather than a zero, and `:benchmarks:ceiling` is unchanged.
- [ ] **`spec-0065-page`** — the warning, tile and JSON on the HTML report,
      and the markdown line.
      Done when: a result that ran out of room names which limit and what it
      reached, one that did not shows nothing, an unmeasured one says so.
- [ ] **`spec-0065-void`** — a rung voided by the injector's own limits, and
      the progress line saying which.
      Done when: a rung reporting `ranOutOfRoom()` is `Void`, the search stops
      there as for `lostGround()`, and the rung line names the limit.
- [ ] **`spec-0065-record`** — `docs/what-it-costs.md`, re-run with sampling.
      Done when: the table carries peak descriptors and ports per rate, and the
      paragraph that cannot separate client from server either does or says
      what is still missing.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :benchmarks:ceiling
```

The ceiling must not move (0057's rule), and the ten-thousand row must say which
end ran out.

## Open questions

1. **How often, and on whose thread?** Recommend one second on its own thread: a
    millisecond is wrong for file reads, and the progress sampler would make a
    measurement vanish under `Progress.silent`.
2. **Is CPU bent into `Headroom` or given its own type?** Recommend `Headroom`
    in hundredths of a core — 391 against 400 asks what 61,120 against 65,536
    asks — though a fraction type is honester about it not being a hard limit.
3. **Does CPU void a rung?** Recommend no — reported, never gated on.
    `docs/what-it-costs.md` says generator and target sharing all four cores "is
    what a laptop run and a single CI runner both look like", so a share near
    saturation is ordinary rather than a fault and gating on it would void most
    honest laptop runs. Descriptors and ports are hard ceilings, and those void.
4. **`/proc/net/sockstat` is machine-wide.** Its `tw` counts every socket on the
    host, so a neighbour's TIME_WAIT lands here; per-process means matching
    `/proc/self/fd` inodes against `/proc/net/tcp`, tens of thousands of lines a
    second. Recommend the cheap one, labelled machine-wide, as the range is.
5. **Does the descriptor limit belong on `Machine`?** Recommend not. An unlike
    `Machine` already warns on every comparison (`Comparison.kt:48`), and a
    ulimit differing between a laptop and a container would warn on runs that
    compare fine. The limit travels in `Limits`, on the run it bounded.
