# 0018 — A report that explains itself

## Problem

The page prints correct numbers and leaves the reader to know what they mean.
That assumes a reader who is comfortable with percentiles, and most people
reading a load test are not — they are trying to find out whether to ship.

The misreadings are predictable, and every one of them can be caught by the
report itself:

- **A p99 backed by five requests is quoted like a fact.** A four-second run at
  120 a second gives 480 samples, so p99 rests on five of them and moves by
  tens of milliseconds between runs.
- **Percentiles are added up.** A journey of three steps is reported as
  p99(a) + p99(b) + p99(c), which is a number no user ever experienced.
- **p99 is read as "99% of users are fine."** A user making ten requests meets
  the p99 about one time in ten.
- **Max is read as a trend.** It is one request, and it is the one most likely
  to be a garbage collection.

## Not doing

- No SLO configuration, no thresholds to set. A test asserts; the page reports.
- No averages, anywhere, ever. A mean latency hides exactly the thing a load
  test is looking for, and adding one to be friendly would be the worst
  possible way to be friendly.
- No advice about what to fix. The page says what was measured and what that
  does and does not support; the reader knows their system.
- No change to the markdown or the job summary in this spec.

## Shape

A block above the table, in sentences, built only from what was measured:

> **1,440 requests, 43 failed (3.0%).** The run kept to its schedule, so these
> latencies are the target's.
>
> **`/pay` is the slowest step** at 214 ms p99, and it carries 43 of the 43
> failures.
>
> **p99 here rests on 5 requests per step.** Treat it as a hint, not a number:
> a longer run is the only way to firm it up.
>
> A user completes 3 steps, so about **1 user in 34** meets a p99 somewhere in
> the journey. Percentiles do not add — the journey's own p99 is not the sum of
> the steps'.

- Each percentile column carries how many samples sit at or beyond it, so `p99`
  reads `p99 (5)` when it rests on five requests.
- A step whose count is below the one before it says how many users were lost
  there.
- Criteria are stated where they are applied. "Slowest" means highest p99;
  "kept to its schedule" is `fellBehind()`.

## Why this shape

Everything above is derived from counts the run already has, so the page is
still only reporting measurements — it is doing the arithmetic a reader would
otherwise have to do, and the arithmetic they usually get wrong.

The sample-count annotation is the important one. It is the difference between
a report that hands over a p99 and one that says how much weight it will bear,
and it costs one number that is already known.

Saying "one user in 34" rather than "99th percentile" is the same fact in the
units the reader actually cares about, and it is exact: 1 - 0.99³.

## Stack

- [ ] **`spec-0018-weight`** — sample counts behind each percentile, in the
      table header and in the summary.
      Done when: a run of 480 per step shows p99 resting on 5, and a run of a
      million does not shout about it.
- [ ] **`spec-0018-reading`** — the summary block: failures, schedule, slowest
      step, journey risk, and the note that percentiles do not add.
      Done when: each sentence appears only when its criterion holds, and the
      golden holds them.
- [ ] **`spec-0018-losses`** — users lost between steps, named where it
      happened.
      Done when: a scenario whose second step always fails reports the users
      that never reached the third.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

Answered by the architect:

1. **"Rests on N requests" is flagged below 100.** Under that a percentile
    moves visibly between runs; above it the warning is noise.
2. **Journey risk assumes independence between steps**, which is not quite
    true, and the page says so in the sentence rather than in a footnote
    nobody reads.
3. **No colour beyond what already exists.** Red for failures, and nothing
    else, so the one red thing on the page still means something.
