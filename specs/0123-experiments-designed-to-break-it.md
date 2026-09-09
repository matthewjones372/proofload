# 0123 — Experiments designed to break it

## Problem

The repository asserts coordinated-omission protection in three places — 0003's
two clocks, `docs/concepts.md`, the README's opening paragraph — and tests it
nowhere adversarially. Every existing timing test is a *confirmation*: a run
happens, the numbers look sensible, the test passes. None is built so that a
plausible bug would make it fail.

The claim is falsifiable and is not currently being falsified. `[CO-MEASURED]`
is the shape of the evidence needed: same run, 44 ms uncorrected max against 46
seconds corrected. Proofload can produce both numbers itself — it holds
`serviceTime` and `responseTime` for the same samples — so the experiment is a
comparison it can run against its own two clocks and against a model computed
outside the engine entirely.

## Not doing

- No new production code, and none of these experiments is a gate on `build`.
  They are the `timing` lane and a benchmark task, per AGENTS.md.
- No comparison against another tool. `[CO-WRK2]` is cited, not benchmarked.
- No network. Every target here is in-process and deliberately controllable;
  a stall injected by a socket is a stall nobody can predict the size of.
- Not a replacement for 0011's ceiling or 0056's socket number.

## The oracle

The thing that makes these tests falsifying rather than confirming.

For a constant-rate open profile the correct answer needs no load generator.
Given requested arrivals `a[i]` (a pure function of *i*, from
`InjectionProfile.departures()`) and a target whose service time at wall-clock
time *t* is a known function `s(t)`, an ideal generator with unbounded capacity
produces exactly:

```
departure[i] = a[i]                    // never later
service[i]   = s(a[i])
response[i]  = s(a[i])                 // lateness is zero by definition
```

`ReferenceRun` computes that in core-test as plain arithmetic — no threads, no
clock — and freezes it into a `RunResult` through the public `RunRecorder`. Each
experiment then states a **tolerance**, and the test is that Proofload's
measured percentiles sit inside it. A coordinated-omitting generator cannot get
inside the tolerance on any of the stall experiments; that is what makes the
comparison worth running.

## The experiments

Each is: a workload, a target `s(t)`, the oracle's answer, the observation that
would prove omission, and the pass criterion.

**1 — Sudden stall.** 100/s for 20 s. `s(t) = 1 ms`, except `s(t) = 5 s` for
t ∈ [10 s, 15 s]. Oracle: 500 samples at ~5 s, response max ≥ 5 s, p99 ≥ 4 s.
*Omission looks like*: ~1 sample over 1 s and a p99 near 1 ms. *Pass*: response
p99 within one bucket width of the oracle's, and the count in [10 s, 20 s]
within 1% of 1,000. **Service time may legitimately differ** — the target was
answering — and the test asserts on response time only.

**2 — Long pause then recovery.** As 1, with the stall 30 s inside a 60 s run.
*Pass*: `heldScheduleFor` names the second the schedule went; post-recovery
seconds return to the pre-stall distribution within tolerance; the run is
`Partial`, never `Valid`.

**3 — Bimodal.** 200/s for 30 s, `s` = 5 ms for 95% of requests and 500 ms for
5%, chosen by the user number so the split is reproducible. *Pass*: p50 in the
fast mode, p99 in the slow one, and **`mean` between them and equal to neither**
— the case where quoting an average would hide the whole finding.

**4 — Queue build-up.** A target with one server and a 20 ms service time, at
100/s. Offered load is twice capacity, so the queue grows without bound and the
oracle is `response[i] ≈ (i - capacity·t)·20 ms`. *Pass*: measured response time
grows linearly across the run's seconds; a flat line is the omission signature.

**5 — Periodic stalls.** 50 ms of stall every second, for 60 s. *Pass*: the
per-second timeline shows the period; the whole-run p95 is materially above the
p50; the count per second is flat — the departures did not stop.

**6 — Latency above the arrival interval.** 1,000/s with `s = 50 ms`. Fifty
users in flight at all times by construction. *Pass*: Little's law ratio within
`LAW_TOLERANCE` of 1 at L ≈ 50, and `behind.p99` under one interval. A
generator that waits would report ~20/s.

**7 — Latency above the whole run.** 10/s for 5 s, `s = 60 s`. Fifty arrivals,
none answered inside the window. *Pass*: the run does not deadlock, does not
truncate the journeys, and reports 50 samples at ~60 s once they land — or, if
it declines to wait, says so as a doubt rather than reporting 0 requests and a
green run. **This case has no defined behaviour today** and is the one most
likely to produce a plausible empty result.

**8 — Generator starvation.** Experiment 1's workload with the carriers held by
a scenario arm that blocks a carrier per user (0126's pinning target). *Pass*:
the run is not `Valid`. Today it is — this experiment is expected to fail on
first writing, and that is the point.

**9 — Generator CPU saturation.** As 8, with a CPU-burning step body instead of
a blocking one, sized to occupy every core. *Pass*: as 8.

## Pass/fail, stated once

An experiment fails if a measured percentile is outside the oracle's tolerance
**in the direction of looking better than reality**. Slower than the oracle is a
finding to investigate; faster is the bug this whole spec exists to catch, and
is never tolerated.

## Stack

- [ ] **`spec-0123-oracle`** — `ReferenceRun` in core's test sources: arrivals,
      a service-time function, a frozen `RunResult`, no threads.
      Done when: an oracle for experiment 1 reports response p99 ≥ 4 s and
      service p99 ≈ 5 s from arithmetic alone.
- [ ] **`spec-0123-stalls`** — experiments 1, 2 and 5 against an in-process
      target, in the `timing` lane.
      Done when: each passes against Proofload and fails against a deliberately
      omitting generator written in the same test source.
- [ ] **`spec-0123-shapes`** — experiments 3, 4 and 6.
      Done when: the build-up run's per-second response time is monotone and the
      bimodal run's mean sits between its two modes.
- [ ] **`spec-0123-unbounded`** — experiment 7, and whatever `RunResult` has to
      say for it.
      Done when: a target slower than the run either produces its samples or
      produces a doubt, and never produces an empty `Valid` run.
- [ ] **`spec-0123-starvation`** — experiments 8 and 9, expected red until 0126.
      Done when: both are red, tagged, and named in `ROADMAP.md` as the open
      hole rather than deleted for being red.

## Acceptance

```bash
./gradlew :examples:timingTests
./gradlew build
```

## Open questions

1. **How wide is "tolerance"?** Recommend one histogram bucket at the magnitude
   of the claim, read off `Timing.precision` rather than chosen — 0047 already
   makes the number carry its own width.
2. **Does the omitting generator live in the tree?** Recommend yes, in test
   sources: an experiment that cannot fail proves nothing, and the cheapest
   proof that it can is a generator it catches.
3. **Experiment 7's correct behaviour** is genuinely undecided. Recommend a
   bounded wait declared on the run — the shape `completing`'s `drainingFor`
   already uses — rather than an unbounded `awaitAll`, and 0128 owns it.
4. **Is 4's single-server target honest?** It is a model, not a service.
   Recommend keeping it and labelling it: the point is a queue whose growth is
   known in advance, and only a model gives that.
