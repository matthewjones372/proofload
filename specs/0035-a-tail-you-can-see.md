# 0035 — A tail you can see

## Problem

`Timing` carries p50, p95, p99 and max, and nothing between p99 and the single
worst sample of the run. The percentile that settles most JVM questions is
p99.9: G1 and ZGC are practically identical up to p99 and separate only above
it, where G1's pauses run past 20 ms and ZGC's stay near 50 microseconds
`[ZGC]`. A reader who wants that number today sums `distribution` by hand.

`Histogram.percentile` already answers any percentile at 0.78% relative error,
and 0021 added `Timing.interval(percentile)` on top of it, so the machinery is
there twice over. Gatling's own reporting is bucketed as integer milliseconds
with an error stated as under 10% `[GATLING-PCT]`, which cannot resolve a
question about a tail at all. This tool can answer something its reference point
cannot, and does not print it.

## Not doing

- No p99.99. It needs tens of thousands of samples per step to mean anything,
  and a field that is usually noise is worse than no field.
- No configurable percentile list on the page. 0018 owns the report.
- No change to how a percentile is computed or rounded. 0003 settled that.
- No change to `interval`. A tail percentile gets its sampling interval from
  0021 like every other.

## Shape

```kotlin
import io.github.matthewjones372.kestrel.p999

result[pay].serviceTime.p999                  // 3s 200ms
result[pay].serviceTime.percentile(99.95)     // anything else, from the buckets

val simulation = checkout.at(50.perSecond, over = 1.minutes)
    .expecting(p999(pay) under 1.seconds)
```

- `Timing.p999` — a field, beside its neighbours.
- `Timing.percentile(Double)` — read off `distribution`, so a frozen timing can
  answer a percentile nobody asked for at freeze time.
- `p999(step)` — the goal builder, beside `p50`, `p95` and `p99`.

A step with fewer than a thousand samples reports `p999` as absent with its
reason, the way a measurement that cannot be made already does.

## Why this shape

A field and a function rather than one or the other. `p999` is the number people
ask for by name and should read like its neighbours; `percentile()` stops the
next request for a percentile being another spec. Both read the same buckets, so
they cannot disagree, and 0021's interval already tells a reader how much room
the answer has.

Refusing to print `p999` under a thousand samples is the same principle as
refusing to interpolate. One sample in a thousand, from a run of four hundred,
is a number nobody measured, and printing it invites a decision it cannot carry.

## Stack

- [ ] **`spec-0035-percentile`** — `Timing.percentile(Double)` off the frozen
      buckets, and `p999` beside `p99`.
      Done when: `percentile(99.0)` equals `p99` for the same timing, and a
      timing under a thousand samples reports `p999` absent with its reason.
- [ ] **`spec-0035-goal-and-page`** — the `p999` goal builder, and the tail on
      the report with its interval.
      Done when: a `p999` goal is judged and rendered like any other, and the
      golden holds the absent case.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Is `distribution` always present on a frozen `Timing`?** It defaults to
    empty, which would leave `percentile()` unanswerable for a timing built from
    samples rather than run. Recommend making it required.
2. **A thousand samples, or a hundred?** Recommend a thousand: below that the
    top bucket holds fewer than one expected sample. Wherever the number lands
    it lives in one place and the page reads it.
3. **Does `max` stay?** Recommend yes. It is the only number in the set that is
    an observation rather than an estimate, and it is how a reader spots a hang.
