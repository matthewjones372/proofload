# 0030 — A baseline in CI

## Problem

Spec 0021 compares a run to a baseline and says nothing about where the
baseline comes from or whether the two runs are comparable at all. Both gaps
bite immediately in CI.

**Nowhere to put it.** A team on GitHub with no object store has to work out
artifacts, caches or a side branch for themselves, and will get it wrong in a
way that silently compares nothing: a missing baseline currently just means no
comparison, quietly.

**Nothing says the two runs are alike.** A baseline taken at 50 a second is
compared to a soak at 500 without complaint. A baseline from a four-core
GitHub-hosted runner is compared to one from an eight-core machine, and the
difference between the runners is reported as a regression in the service. On
shared runners that is not an edge case, it is most runs: this repository's own
benchmark shows p99 tails dominated by machine stalls rather than by load.

A tool that reports a noisy neighbour as a performance regression gets its gate
switched off within a month.

## Not doing

- No storage service and no Proofload-hosted anything. A baseline is a file, and
  this spec explains where to keep it.
- No GitHub Action of our own. A workflow file and a cache key.
- No automatic gating. A comparison is reported and can be asserted on; whether
  a build fails is the team's call, and on shared runners it should not be
  latency that fails it.
- No statistical modelling of runner noise. Refusing to compare unlike things
  beats modelling the difference.

## Shape

```kotlin
result.writeBaseline(path)            // now records the plan and the machine

when (val comparison = result.against(readBaseline(path))) {
    is Comparison.NotComparable -> println(comparison.why)   // different plan, different machine
    is Comparison.Compared -> comparison.changes.forEach(::report)
}
```

- The baseline records the **plan** — scenario, steps, profile — and the
  **machine**: cores, JDK, os and arch.
- Comparing across a different plan is `NotComparable`, naming what differs.
- Comparing across a different machine is reported, not refused: a warning on
  the page, and a line in the comparison saying every delta may be the runner.
- A **calibration probe**: a fixed, target-free measurement run before the
  load, recorded in the baseline. A runner materially slower than the one that
  made the baseline is named as the likely cause before any step is.
- `docs/cookbook.md` carries the GitHub recipe: `actions/cache` keyed by run id
  with a prefix restore-key, the artifact fallback via `gh run download`, and
  the orphan-branch option for a baseline somebody reviews.

## Why this shape

Refusing to compare unlike runs is the whole feature. Everything else here —
the intervals, the buckets, the honesty about sample size — is undone by
comparing a smoke run to a soak, and no amount of statistics rescues it.

The machine is a warning rather than a refusal because a team that only ever
runs on GitHub-hosted runners would otherwise never get a comparison at all.
They should get one, with the caveat attached to it.

The calibration probe is what turns "this might be the runner" into a number.
It costs a second at the start of a run and answers the question a reader will
otherwise answer with a guess.

## Stack

- [x] **`spec-0030-provenance`** ([#20](https://github.com/matthewjones372/proofload/pull/20)) — the plan and the machine in the baseline
      format, and `Comparison.NotComparable`.
      Done when: two runs of different profiles refuse to compare and say why,
      and two runs of the same profile on different machines compare with a
      warning.
- [ ] **`spec-0030-calibration`** — the probe, recorded and compared.
      Done when: a run on a machine half as fast reports the machine before it
      reports a step.
- [ ] **`spec-0030-recipe`** — the GitHub section of the cookbook, and a
      workflow in this repository that uses it on its own examples.
      Done when: a pull request here shows a comparison against `main`'s
      baseline in its job summary.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

Answered by the architect:

1. **A missing baseline is reported, not ignored.** "No baseline found" on the
    page is the difference between a first run and a broken cache key, and
    silently skipping the comparison hides the second.
2. **The probe is CPU-only** — no allocation, no IO — so it measures the runner
    rather than the runner's disk or its network.
3. **The docs say plainly that latency should not gate a merge on shared
    runners.** Failures and errors should. A gate that fails a third of the
    time because of a noisy neighbour is a gate that gets deleted, and it takes
    the useful part with it.
