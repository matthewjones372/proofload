# 0043 — Void is not the same question as behind

## Problem

0031 calls a rung **void** when `fellBehind()` is true: the injector could not
offer the load, so nothing was learned about the target. The idea is right and
the gate is borrowed from somewhere it does not fit.

`fellBehind()` asks whether the generator's backlog is large enough to have
moved a number the report prints, and it answers by comparing the backlog
against the histogram's error bar:

```kotlin
behind.p99 > worst * Histogram.PRECISION      // PRECISION is 1/128
```

That is a good question for a page footer and the wrong question for a rung. It
declares a rung void unless the target's p99 is roughly **128 times** the
injector's own scheduling delay. Kestrel's per-departure cost is 0.5–10 ms, so
a rung only counts if the target takes well over a second. Measured while 0031
was built: a 250 ms target at 20 users a second gave a `behind.p99` of 10.3 ms
against a response p99 of 267 ms — void, though the generator had kept its
schedule perfectly well.

The consequence is that every rung of an in-process search voids, the search
stops at the first rung, and `Capacity` refuses to name a rate. The engine tests
for 0031 can prove the wiring and the void path and nothing else; the claim that
a search reports the rate and names the goal is proven in core against a
synthetic judge. At real scale — two-minute holds, network latency, hundreds of
thousands of lateness samples — it is probably fine, and "probably" is the
problem. Nothing in the repository demonstrates it.

## Not doing

- No change to `fellBehind()`. It answers its own question correctly and the
  report footer is right to ask it.
- No change to the ladder, the bisection or `Capacity`'s shape. 0031 settled
  those.
- No warm-up. 0032 owns that, and a rung that voids on its first cold second is
  a case for 0032 rather than for a second rule here.
- No auto-retry of a void rung. A rung that could not be offered is a fact to
  report, not to paper over.

## Shape

```kotlin
rung.outcome            // Passed / Failed / Void, as now
rung.offered            // 4,782/s of the 4,800/s the rung asked for
```

The gate becomes a question about the **schedule** rather than about the
response:

- A rung is void when the injector's lateness is large against the interval
  between departures it promised — that is, when the generator is losing ground
  on its own rate — rather than when lateness is large against the target's p99.
- At 4,800 a second the interval is 208 microseconds, so a p99 lateness of ten
  milliseconds is fifty departures of backlog and the rung is plainly void. At
  20 a second the interval is 50 ms, the same ten milliseconds is a fifth of one
  departure, and the rung is fine — which is the case that is wrongly void
  today.
- `offered` makes the judgement visible instead of implicit: a reader can see
  how much load actually left, and the page can show it per rung on the curve.

## Why this shape

Three candidates. Comparing lateness to the target's p99 is what is there now
and couples the generator's health to the target's slowness, so a fast target is
unmeasurable and a slow one hides real backlog. Comparing the departures that
actually left against the departures the profile promised is the most direct
statement of "could the injector offer this load", and it is a count rather than
a percentile. Comparing lateness against the inter-departure interval is the
same claim expressed as a time, and it degrades smoothly as the rate climbs,
which is exactly the axis a search moves along.

Recommend the third as the gate and the second as the reported number, since
they are the same fact and readers ask for it in both forms. Both are already
derivable from what the recorder keeps.

This also removes a coupling that would have bitten twice: as written, adding
`Step.Emit` from 0040 — where response times are pipeline latencies of seconds —
would make voidness easier to avoid the slower the pipeline got.

## Stack

- [ ] **`spec-0043-gate`** — the void gate as lateness against the promised
      inter-departure interval, and `Rung.offered`.
      Done when: a rung against a fast in-process target at a rate the generator
      keeps is not void, a rung at a rate it cannot keep is void, and the
      threshold lives in one named place rather than being borrowed.
- [ ] **`spec-0043-proved`** — an engine test that finds a real rate against a
      real target, replacing the synthetic-judge-only proof.
      Done when: a search against an in-process target that fails a p99 goal
      above a known rate reports that rate and names the goal, run end to end on
      the engine.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :examples:timingTests
```

## Open questions

1. **How much lateness is too much?** A p99 lateness above one full departure
    interval means the generator is a departure behind at the tail. Recommend
    that, stated in one place and printed on the page, because it is the
    threshold a reader can check rather than a constant they must trust.
2. **Should a void rung still contribute to the curve?** Recommend yes, drawn
    and labelled void: the rate at which the generator itself runs out is a
    useful thing to see, and hiding it invites someone to re-run and wonder.
3. **Does `Capacity.rate` stay null when a rung voids?** Recommend yes,
    unchanged. Naming a rate below a void rung would report the generator's
    ceiling as the target's, which is 0031's whole argument.
4. **Does this need the injector's own cost measured?** 0039's hiccup recorder
    measures exactly that. Recommend not depending on it: the schedule gate is
    self-contained, and a rung that voids because the injector stalled is
    correctly void either way.
