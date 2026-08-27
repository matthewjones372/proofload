# 0048 — A floor measured where the claim is

## Problem

0039's `calibrate()` runs a null step repeatedly and reports `resolution`: the
spread of those repeats, as a fraction of what they measured. On an idle
machine that is 0.007 to 0.046. Under a load average of 135 on eight cores,
spec 0042 measured it at **1.07 to 1.25** — 107% to 125%, against a
`Floor.UNUSABLE` of 0.40.

Read literally, that says no comparison on a busy developer machine can ever be
believed, and 0039's open question 4 then says the page should print a refusal
in place of the comparison. That is not a useful tool and it is not true
either.

The mistake is one of magnitude. A null step's median is tens of microseconds.
When the machine is busy that median moves from, say, 50 µs to 110 µs — which
is 120% *relative* and about 60 µs *absolute*. A 250 ms target measured on the
same machine at the same moment does not move by 120%. It moves by roughly the
same 60 µs, which at 250 ms is a fifth of a percent.

**Relative noise does not transfer across magnitudes; absolute noise does.**
`resolution` is measured at the null step's magnitude and then applied as a
relative bound to a statistic that is three or four orders larger. This is the
same error 0039 already found one level in — where a floor taken at p99 was
meaningless because a null step's p99 *is* the machine's stalls — arrived at
from the other direction.

There is a second, separate mistake underneath it. 0042 works around the
relative floor by also requiring a difference to clear `hiccups.p99` in absolute
terms, and that is **the wrong absolute quantity**. The hiccup recorder measures
what the *injector's own JVM* stalled for. What a comparison needs bounding is
the spread of *repeated identical measurements of a target* — which includes the
target's scheduling, its own JIT, and everything else the machine does to it,
none of which the injector's hiccup thread can see. The two are different
quantities and the smaller one is standing in for the larger.

That gap is measured, not supposed. On `main`, with 0042's fix in place,
`RegressionTest` still fails roughly one run in three: three consecutive runs of
`./gradlew :examples:timingTests` gave one failure and two passes, the failure
at the unchanged pair, where both gates passed and the comparison called a
difference real that nothing in the code had caused.

## Not doing

- No removal of `resolution`. It is the right number when the statistic being
  compared is of the null step's magnitude, and it is what makes a small,
  fast target's comparison honest.
- No modelling of the noise. 0039 settled that measuring is enough.
- No change to the hiccup recorder, which measures an absolute thing and is
  already correct.
- No change to what a comparison concludes. 0038 owns the verdicts; this owns
  what they are given to judge against.

## Shape

```kotlin
val floor = kestrel.calibrate()

floor.absolute            // 61us — how far a repeat of one measurement moves here
floor.resolution          // 1.25 — the same, as a fraction of what was measured
floor.resolves(1.04, of = 250.milliseconds)   // true: 10 ms is well past 61us
```

- `Floor` gains the **absolute** spread beside the relative one. It is the same
  measurement expressed the way it actually transfers — the spread of the
  probe's repeats in duration, not the injector's stalls, which is a different
  and smaller thing.
- `resolves` takes the magnitude the claim is being made at, so it can apply the
  absolute bound rather than a fraction taken elsewhere.
- The page states both: what the machine's own movement was, and what fraction
  of *this* statistic that comes to.

## Why this shape

Three ways out. Re-basing the null step so its magnitude resembles a real
target means calibrating against a fake delay, and a fake delay is a thing this
tool would then be measuring instead of the machine. Dropping the relative
figure entirely loses the case it is right for and makes a fast target's floor
unreadable. Carrying both, and choosing by the magnitude of the claim, keeps
each number where it is true.

The absolute figure is also the one already proven to work. Spec 0042 needed
exactly this to stop `RegressionTest` flaking, and what it landed requires a
difference to clear both the relative bound and `hiccups.p99` in absolute
terms — five runs out of five passed under a load average of 135, where the
relative gate alone would have refused every one.

## Stack

- [ ] **`spec-0048-absolute`** — the absolute spread on `Floor`, and `resolves`
      taking the magnitude of the claim.
      Done when: a floor measured on a loaded machine refuses a difference of a
      few microseconds at any magnitude, does not refuse a ten-millisecond
      difference at 250 ms, and the page prints both figures.
- [ ] **`spec-0048-consulted`** — 0038's comparison, 0042's test and the report
      reading the new shape.
      Done when: a comparison on a loaded machine returns a verdict rather than
      refusing everything, a difference genuinely inside the machine's own
      movement still reports `CannotTell` naming it, and
      `./gradlew :examples:timingTests` passes ten consecutive times on a loaded
      machine — the count matters, because the present failure rate is about one
      run in three and a single green run proves nothing.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :examples:timingTests
```

and, for the claim that matters, with the machine busy.

## Open questions

1. **Does `UNUSABLE` survive?** As a relative figure it fires constantly. As an
    absolute one it needs a magnitude to be a fraction of. Recommend keeping the
    idea but expressing it per comparison — this machine cannot support *this*
    claim — rather than as a property of the machine alone.
2. **Which magnitude does a comparison use — the baseline's or the candidate's?**
    Recommend the baseline's: it is the number the reader already had, and using
    the candidate's would let a regression widen the band that judges it.
3. **Should `calibrate()` measure at more than one magnitude?** It could run the
    null step and a deliberately slow step and report both. Recommend not yet:
    it doubles the calibration's cost for a curve nobody has asked to see, and
    the absolute figure is what transfers anyway.
4. **Does this invalidate anything 0042 shipped?** It does not remove the need
    for it, but 0042 is not yet sufficient: it gates on `hiccups.p99`, which
    bounds the injector rather than the machine, and it still fails about one
    run in three. Recommend 0042's `separates` helper be deleted and replaced by
    the new `Floor` call, and that the ten-run check above be the thing that
    closes it.
