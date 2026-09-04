# 0097 — A threshold that means material

## Problem

`fellBehind` decides whether a run's latencies are the target's or partly this
tool's, and it is the verdict everything else rests on: 0087 puts it above every
goal, and the README opens by saying a green run on a generator that fell behind
is the trap Kestrel exists to close.

It compares the run's own lateness against the worst step's response time scaled
by `Histogram.PRECISION` — 0.0078125. A run measured today against a target with
a 356 ms tail was called behind on 5.27 ms of lateness, because 5.27 exceeds
0.78% of 356. The lateness was a **constant** 5.24 ms in every second of a
thirty second run, `lostGround` was false, Little's law agreed at a ratio of
1.05, and the per-second timeline was flat from first second to last. Nothing
about that run was queueing, and the verdict said its numbers were not the
target's.

`PRECISION` is the width of a histogram bucket: how small a difference this tool
can *see*. The threshold asks whether the lateness is detectable, and reports the
answer as though it had asked whether the lateness matters. Those are different
questions, and a verdict that answers the first while claiming the second is one
people learn to ignore — which costs more than having no verdict at all.

## Not doing

- **Not removing the verdict**, and not softening what it says when it fires.
  The failure mode this exists to catch is real and the wording stays.
- **No new statistic.** Everything below is already measured on every run.
- **No configuration.** A threshold a caller can lower is a threshold that gets
  lowered until it stops complaining.
- **Not touching `lostGround`**, which compares lateness against the interval
  and answered correctly on the run above.

## Shape

Judge the lateness against what it would move, and say which test fired.

```kotlin
result.fellBehind()          // unchanged in name and meaning
result.schedule.because      // which comparison decided it, for the page and the JSON
```

Three candidate rules, all computable from what a run already holds:

1. **A share of the tail.** Lateness p99 above some percent of the worst
   response-time p99 — the current rule with a materiality figure rather than a
   bucket width. Recommend this, at 5%: a tail inflated by a twentieth is a tail
   worth distrusting, and 0.78% is not.
2. **Against the floor.** 0039 measures what this machine can resolve; lateness
   under that is invisible whatever share it is. Good, and it needs a
   `calibrate()` nobody runs by default.
3. **Against Little's law.** A run whose observed concurrency matches its
   predicted one is not holding a queue, whatever the lateness looks like. The
   most direct evidence and the least direct to explain.

Recommend 1 as the rule, with 3 as a second opinion the page prints where the
two disagree — the run above would have been called kept by both.

## Why this shape

The bucket width is not a materiality threshold and was never chosen as one; it
is what the histogram happens to resolve, doing a job nobody assigned it. Any
figure here is a judgement, so the honest move is to pick one, say it is a
judgement, and put it where a reader can see it — rather than inherit one from a
constant that means something else.

Five percent is arguable and is meant to be arguable. What is not arguable is
0.78%, which called a flat run behind and would call almost any fast target
behind.

## Stack

- [ ] **`spec-0097-threshold`** — the share, named as a constant with the
      judgement written beside it.
      Done when: the run in Problem is judged as having kept its schedule, and a
      run whose lateness is a fifth of its tail is not.
- [ ] **`spec-0097-because`** — the comparison that fired, carried on the result
      and into 0087's `schedule` block.
      Done when: a document says which of the two tests decided, and the remedy
      quotes that one's numbers.
- [ ] **`spec-0097-law`** — Little's law as a second opinion where the two
      disagree, on the page.
      Done when: a run called behind whose concurrency agrees says so.

## Acceptance

```bash
./gradlew build
```

## Open questions

- **Is five percent right?** Recommend it as a starting figure and expect it to
  move once there are runs to move it against. It is a judgement either way; the
  fix for a bad judgement written down is easier than for a good one inherited.
- **Should `fellBehind` consult the floor when one was measured?** Recommend
  yes, as a floor beneath the share: lateness under what the machine can resolve
  is not evidence of anything. It only applies to runs that called `calibrate()`.
- **Does an existing baseline's verdict change?** Yes, and it should — a stored
  run judged by the old rule was judged by a bucket width. Recommend saying so in
  the changelog rather than versioning the baseline format for it.
