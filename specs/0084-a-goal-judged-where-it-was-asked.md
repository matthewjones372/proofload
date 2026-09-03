# 0084 — A goal judged where it was asked

## Problem

0077 split a staged run at its own boundaries, so a ramp and the hold after it
are two answers rather than a mixture. It deliberately left the goals alone:
"a `Goal` is judged against a run. Judging one per stage is a different feature
and wants its own verdict shape."

That leaves the page and the verdict disagreeing. The stage table says the ramp
answered in 21 ms and the hold in 403 ms; the goal beside it says
`p99 under 300ms` and judges it against a mixture of the two
(`RunResult.kt:576-578`, `Goal.judge(result)`). A run whose hold plainly missed
can meet the goal because the ramp was long enough and easy enough to pull the
aggregate under the line — which is the same defect 0077 was written to fix,
one level up, and now the more visible one because the stage table is printed
directly above the verdict that ignores it.

Lengthen the ramp and the verdict flips. That is a green test somebody earned
by editing the profile.

## Not doing

- **No new goal types.** `PercentileUnder`, `FailureRateUnder`, `GoodputAtLeast`
  and `KeptSchedule` are the goals; this is about where each is judged.
- **No per-stage percentiles beyond what 0077 derived.** A stage's numbers are
  the timeline's, at the timeline's width, and a goal judged on them says so.
- **No goals on a run nobody staged.** One stage is the run, and a second
  verdict saying the same thing is noise.
- **No change to what a goal means.** `p99 under 300ms` asks the same question;
  this decides which population it asks it of.
- **No automatic per-stage judging.** A goal that silently became four goals
  would change every existing run's verdict count.

## Shape

```kotlin
val checkout = scenario("checkout") { }
    .at(hold(100.perSecond, over = 2.minutes) then rampTo(1000.perSecond, over = 5.minutes))
    .expecting(p99(placeOrder) under 300.milliseconds inEveryStage)
```

```kotlin
/** The same goal, asked of each stage rather than of the run. */
val Goal.inEveryStage: Goal

/** Which stage a verdict is about, or null where it is about the run. */
val Verdict.stage: Stage?
```

- `inEveryStage` wraps a goal. Judging it answers one `Verdict` per stage, each
  naming its stage, and the run meets it only where every stage does.
- A verdict about a stage carries the stage, so the report can put it on the row
  it belongs to rather than in a list that repeats the goal's name four times.
- The precision travels: a stage's numbers are the timeline's coarse buckets
  (0047, 0077), so a verdict off them says what it is good to, and a goal
  missed by less than that reports as *cannot tell* rather than as missed.
- `KeptSchedule` per stage is the one that pays for itself twice: a run that
  held its schedule for the flat two minutes and lost it climbing is a run
  whose ramp found the ceiling, and the aggregate answer hides exactly that.

## Why this shape

**A wrapper, not a flag on every goal.** `p99(step) under 300.milliseconds` is
a value, and `inEveryStage` is a function on it. Putting a `perStage: Boolean`
on each goal type would be four constructors changed and four `judge` methods
branching, and the branch would be in the type rather than around it.

**Opt in.** A goal that silently became four would change the verdict count of
every existing staged run and the meaning of `metEveryGoal`. Somebody asking
for it is asking a sharper question deliberately.

**Every stage, not the worst.** "The worst stage must pass" and "every stage
must pass" are the same rule; naming it after every stage is what makes the
report readable, because the verdict that failed names which stage failed.

**Cannot tell, at the timeline's width.** 0062 established that a difference
smaller than what the machine can resolve is reported as unresolvable rather
than as a result. A stage's percentiles are one significant digit, so a goal
missed by 2% of the value is inside the bucket and the honest verdict is that
this cannot be judged at this resolution — not a red tick somebody chases.

## Stack

- [ ] **`spec-0084-verdict`** — `Verdict.stage`, and a goal that can answer more
      than one verdict.
      Done when: an unstaged run's verdicts are byte-identical to today and a
      verdict about the run has a null stage.
- [ ] **`spec-0084-stage`** — `inEveryStage`, judged off 0077's stages.
      Done when: a run whose hold missed and whose ramp met reports one met and
      one missed verdict, `metEveryGoal` is false, and the same goal without
      the wrapper still meets it off the aggregate.
- [ ] **`spec-0084-resolution`** — a stage verdict missed by less than the
      timeline's width reports as unresolvable.
      Done when: a goal missed by one bucket says so rather than failing, and
      one missed by ten buckets fails.
- [ ] **`spec-0084-page`** — the verdict on the stage row it belongs to, in both
      reports.
      Done when: a staged run's stage table carries its verdicts and an
      unstaged run's page is unchanged.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

A run that met its goal only because its ramp was long now fails, and says
which stage.

## Open questions

1. **Does `GoodputAtLeast` mean anything per stage?** Goodput is over a window
    and a stage is a window, so it should. Recommend judging it per stage and
    checking that the shares add up to the run's.
2. **What does a stage with no seconds answer?** 0077 allows a stage shorter
    than a second, which holds nothing. Recommend `Measurement.Absent("the
    stage recorded nothing")`, which is the answer a step that never ran
    already gets.
3. **Should the aggregate verdict stay beside the per-stage ones?** Both would
    print. Recommend keeping it and labelling it, as 0077 kept the aggregate
    percentiles: it is the number people compare between builds.
