# 0011 — What this tool costs

## Problem

Every claim in this repository rests on one unmeasured assumption: that Proofload
can keep the schedule it promises. `responseTime` counts from the intended
departure precisely so a backlog cannot hide, and `fellBehind()` says when one
is large enough to matter — but nobody has ever run the thing hard enough to
find out where that starts happening.

A load generator that cannot state its own ceiling is asking to be trusted on
the strength of its comments. If it falls over at two thousand a second, that
is worth knowing now, while the design is still cheap to change.

## Not doing

- No comparison against Gatling, k6 or anything else. A benchmark that ranks
  two tools measures the person who wrote it.
- No network. A ceiling measured across a LAN measures the LAN.
- No tuning, no flags, no fast path. This spec finds the number; changing it
  is whatever spec comes after.
- Not in the test lane, and not in the coverage denominator. AGENTS.md is
  explicit that a benchmark of the tool is not a test of the tool.

## Shape

```bash
./gradlew :benchmarks:ceiling
```

```
rate     users    behind p50   behind p99   behind max   kept schedule
1,000    1,000       0.11 ms      0.42 ms      1.9 ms     yes
10,000  10,000       0.30 ms      1.80 ms      9.4 ms     yes
50,000  50,000       2.10 ms     44.00 ms    210.0 ms     no
```

- `benchmarks`, a module that is not published and is excluded from coverage,
  like `examples` already is.
- A JMH-free harness: this measures a whole run's scheduling behaviour, not a
  method's throughput, and JMH is a dependency and a discipline aimed at the
  other question.
- The target is an action that returns immediately without touching a socket,
  so what is measured is the generator and nothing else.
- The output is a table, written to `build/reports/proofload/ceiling.md`, and the
  ceiling — the highest rate that kept its schedule — recorded in
  `docs/what-it-costs.md` beside the machine it was measured on.

## Why this shape

The number that matters is not requests per second. It is the rate at which
`behind` stops being negligible, because past that point every latency the tool
reports is partly its own. Reporting that as one number, with the machine it
came from, is the honest version of a performance claim.

Excluding a socket is deliberate. A real target's latency would dominate, and
the question here is what the scheduler and the recorders cost — the parts a
user cannot swap out.

## Stack

- [ ] **`spec-0011-harness`** — the module, its wiring, its exclusions, and a
      run at one fixed rate reporting its `behind` distribution.
      Done when: `./gradlew build` still ignores it, and the harness prints a
      row for one rate.
- [ ] **`spec-0011-sweep`** — the sweep across rates, the table, and the
      ceiling it picks.
      Done when: the table is written and the chosen ceiling is the highest
      rate whose run answered `fellBehind() == false`.
- [ ] **`spec-0011-record`** — `docs/what-it-costs.md`, with the measured
      ceiling, the machine, the JDK, and what the harness did not measure.
      Done when: the document names a number and the conditions it holds under.

## Acceptance

```bash
./gradlew build          # unchanged: the harness is not in this
./gradlew :benchmarks:ceiling
```

## Open questions

Answered by the architect:

1. **The harness runs for a fixed duration per rate**, not a fixed count, so a
    rate that cannot be sustained still finishes.
2. **A rate is "kept" when `fellBehind()` is false**, the same rule the reports
    use. A benchmark with its own threshold would be marking its own homework.
3. **The numbers are committed with the machine that produced them.** A
    performance figure with no machine beside it is decoration, and a figure
    that changes when someone else runs it is the point rather than a problem.
