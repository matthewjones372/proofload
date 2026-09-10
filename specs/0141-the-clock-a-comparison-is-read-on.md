# 0141 — The clock a comparison is read on

## Problem

`RunResult.against` compares response time and offers no way to ask for
anything else:

```kotlin
else -> compare(name, before.responseTime, now.responseTime, percentile)
```

Response time is service time plus the wait this tool caused, and it is the
right default for the reason a goal's clock defaults there. It is the wrong
number on a run where the generator fell behind, and this tool knows when that
is: the markdown puts **"Behind schedule: 207us late at p99. The response times
below include that backlog"** immediately above the comparison table. So on the
runs where a reader most needs the comparison, the comparison is the one number
the page has just told them to distrust, and there is no argument they can pass
to get the other one.

Found while measuring a pre-encoding change against a Star Wars API. The effect
was a steady 13% of service time at p50, and read through response time at p99
it came back "not distinguishable" on seven rungs of twelve. Both readings are
true; only one of them was available from `against`, so the other had to be
pulled off the runs by hand into a table the load test drew itself.

A second, smaller thing falls out of the same place. The sentence under both
tables is a constant:

> Compared at p99 of response time.

`percentile` has been a parameter since 0021, so that sentence is already wrong
for anyone who passes anything but `P99`. A report that names the wrong
statistic is worse than one that names none.

## Not doing

- **No change to what a comparison decides.** The interval arithmetic, the
  refusal on an unlike plan and the machine caveat are 0021's and stay exactly
  as they are.
- **No second comparison type.** One `Comparison`, one more thing it records.
- **No change of default.** Response time stays the default on `against` for the
  same reason it is the default on a goal: a comparison read on service time can
  look level on a run that never sent the load.
- **No baselines work.** `Difference` and `notWorseThan` are a different spec
  and still wait on the Java-facing baselines facade.

## Shape

```kotlin
fun RunResult.against(
    baseline: RunResult?,
    percentile: Double = P99,
    of: Clock = Clock.ResponseTime,
): Comparison
```

The comparison records what it was read on, so the page can say so rather than
assume it:

```kotlin
data class Compared(
    val changes: List<Change>,
    val before: Machine,
    val now: Machine,
    val beforeProbe: Probe? = null,
    val nowProbe: Probe? = null,
    val percentile: Double = P99,
    val of: Clock = Clock.ResponseTime,
) : Comparison
```

and both reports build the note from those two rather than from a constant:

> Compared at p50 of service time. A sampling interval bounds which sample the
> p50 landed on …

From the other two languages:

```java
Comparison comparison = Results.against(result, baseline);
Comparison onService = Results.against(result, baseline, 50.0, Clock.ServiceTime);
```

```scala
val comparison = result.against(baseline)
val onService  = result.against(baseline, percentile = 50.0, of = Clock.ServiceTime)
```

## Why this shape

A parameter with a default rather than a second function, because the two
readings are the same comparison of the same runs and a caller choosing between
`against` and `againstServiceTime` would be choosing between two spellings of
one idea. The fields on `Compared` are what make the note honest, and they are
the cheapest way: the alternative is passing the clock to every renderer
alongside the comparison, which is the same two values travelling separately and
free to disagree.

`Results.against` and a Scala `against` are in scope here rather than left to a
facade spec, because the Scala surface can already *render* a `Comparison` in
`writeHtmlReport` and `markdown` and has no way to *make* one. A caller who
wants the new clock from Scala today writes `ComparisonKt.against`, which is the
file class 0137 exists to keep out of a consumer's source.

## Stack

- [x] **`spec-0141-clock`** — the `of` parameter, the two fields on `Compared`,
      and both report notes built from them.
      Done when: a comparison on service time reads service time, and the
      golden reports are unchanged for the defaults.
- [x] **`spec-0141-facades`** — `Results.against` in `proofload-java` and
      `against` in `proofload-scala`.
      Done when: a Scala caller builds a comparison without naming a file class.
- [ ] **`spec-0141-docs`** — the cookbook recipe and `docs/from-scala.md`.
      Done when: the page says which clock a comparison is read on.

## Acceptance

```bash
./gradlew build
./gradlew :proofload-report-github:test :proofload-report-html:test
```

## Open questions

- **Should `Compared.of` be nullable, so a comparison built before this spec
  reads back as "unknown clock"?** Recommend no: nothing persists a `Comparison`
  today, so the only values in existence are made by `against`, and a default of
  `ResponseTime` describes every one of them.
- **Should the percentile print as `p99` or as `99th percentile`?** Recommend
  `p99`, matching the goals, the report columns and every other place this
  project names one.
