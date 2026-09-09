# 0129 — A result that cannot lie about itself

## Problem

`RunResult` is a good value and its invariants are enforced by construction in
some places, by a comment in others, and nowhere by a test. The distinction
matters because the value travels: it is frozen, merged across shards (0070),
merged across runs (0037), written to JSON (0087), read back from a baseline
(0030) and compared (0038). Every one of those paths can build a `RunResult`
that the engine would never produce.

What is enforced today: `injectors > 0`; a plan has an arm; a latency is not
negative; `at` is not before the run began; `Timing.precision` is null when
nothing was counted; `Histogram` refuses to merge across precisions; `Runs` and
`Shards` refuse unlike plans, machines and clocks.

What is not:

- **`ok.count + failed.count == count`** holds by construction in
  `StepRecorder.freeze` and by nothing at all in the `data class` constructor.
  A hand-built or deserialised `StepStats` can claim 100 requests, 90 ok and 90
  failed, and every percentile on it will be computed and printed.
- **The timeline and the summary can disagree.** `sum(timeline.count)` is never
  checked against `count`. 0075's multi-sample bodies and the `Drain`'s
  `arrived` path both write to seconds and to the summary separately.
- **The whole-step timings can disagree with their sides.** `serviceTime` is
  frozen as `ok ∪ failed`, and nothing re-checks it after a merge.
- **A partial run is not representable.** There is no field that says this run
  was cut short, so a cancelled run, a run that sent nothing, and a complete run
  are the same shape. 0128 and 0121 add the words; this spec adds the checks.
- **`reached`, `visits` and `attempts` use 0 for "not measured"**, which is also
  a legal count. A baseline written before those fields existed is
  indistinguishable from a run in which nobody arrived.

## Not doing

- **No redesign.** The model is right. This spec states its invariants and puts
  them behind a check that runs where a result is built from outside the engine.
- No new sample storage, no per-request retention. 0003 and 0093 settled that.
- No `require` on the timed path. Every check here runs at freeze, at merge, or
  at deserialisation — never per request.
- No versioning of the JSON format for the `0`-means-unmeasured problem; 0087
  already carries a schema and this is a nullability question inside it.

## The invariants

Numbered, so a test and a report can cite one.

1. Every recorded sample belongs to exactly one step. A sample is recorded
   under exactly one name, once.
2. `step.count == step.ok.count + step.failed.count`, for every step.
3. `result.count == sum(step.count)` and `result.ok + result.failed == count`.
4. `step.serviceTime.count == step.count` and the same for `responseTime`. Both
   whole-step timings are the merge of the two sides and nothing else.
5. `sum(step.failed.reasons.values) == step.failed.count`, with `Other` holding
   the overflow past `MAX_REASONS_PER_STEP`.
6. `sum(timeline[i].count) == count`, and each step's timeline sums to its own
   count.
7. Percentiles are read over the population the goal names: `ok` for goodput,
   the merge for a percentile goal, never a mixture. A percentile over an empty
   population is `Tail.Absent` or `Duration.ZERO` with a count of zero beside
   it — never a number without its count.
8. For every sample, `responseTime = serviceTime + lateness`, so the two
   distributions' counts are equal and the response distribution is never
   *below* the service one at any percentile.
9. `precision` is equal across every timing in a result, or the result was
   merged from unlike sources and is refused.
10. A result knows whether it is complete. `Validity` (0121) and `RunState`
    (0128) are on it, and a result that cannot say reads `Absent`, never `Valid`.
11. Concurrent aggregation loses nothing: the merge of *N* recorders has the sum
    of their counts (0125 proves it under load; this states it).
12. Unmeasured is not zero. `reached`, `visits`, `attempts`, `produced` and
    `usersInFlight` distinguish "nobody counted" from "counted none".

## What the model can and cannot represent today

| State | Representable | How |
|---|---|---|
| complete valid run | yes | the ordinary result |
| complete invalid run | **partly** | `fellBehind()`/`lostGround()` are functions, not a field; 0121 |
| partially completed run | **no** | 0128 |
| cancelled run | **no** | 0128 |
| generator-saturated run | **partly** | `limits` + `ranOutOfRoom()`; carrier starvation not at all (0126) |
| target-failed run | yes | `failed`, `reasons`, `Outcome` split |
| run that sent nothing | **ambiguously** | indistinguishable from a run whose steps were skipped |

Only the last row needs anything new here, and it needs a count of arrivals
(0122) rather than a new result shape.

## Shape

```kotlin
result.check()        // List<Broken> — empty for every result the engine froze
```

- `check()` is a function over the value, not a constructor `require`: a
  deserialiser reading a hostile file wants the list, and a `require` on a data
  class would make an unreadable baseline an exception instead of a finding.
- Called automatically in exactly three places: at `freeze`, at every merge
  (`Runs`, `Shards`), and at JSON read. A break at freeze is an engine bug and
  throws; a break at read is a refusal naming the invariant number.
- `Broken(invariant: Int, described: String, values: Map<String, Long>)`. No
  rendered sentence: core has no opinion on formatting, as `Measurement` already
  establishes.

## Adversarial cases

- A `StepStats` built by hand with `ok.count + failed.count != count`.
- A merge of two shards whose plans agree and whose `precision` does not.
- A baseline written before `visits` existed, read into a comparison.
- A run where the `Drain` recorded `arrived` samples: those have no
  `schedulingDelay`, so invariant 8 must be stated to exclude them explicitly
  rather than to fail on them.
- A run with a `pause` step: the pause records nothing, so the step count and
  the plan's step list differ legitimately.
- A step whose body recorded 100 samples in one visit (0075): `count` 100,
  `visits` 1, `reached` 1 — three numbers that must not be conflated.

## Stack

- [ ] **`spec-0129-invariants`** — `Broken`, `check()`, invariants 2–8.
      Done when: every result the existing suite produces checks clean, and each
      hand-built violation above is named by its number.
- [ ] **`spec-0129-merge`** — `check()` at `Runs` and `Shards`, invariants 9
      and 11.
      Done when: an unlike-precision merge is refused by name and a like one is
      unchanged.
- [ ] **`spec-0129-unmeasured`** — invariant 12: nullable counts where zero is
      currently ambiguous, in the JSON schema and in the readers.
      Done when: an old baseline reads its missing fields as absent and the
      report prints "unmeasured" rather than "0".
- [ ] **`spec-0129-completeness`** — invariant 10, once 0121 and 0128 land.
      Done when: the state table above has no "no" left in it.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Should `check()` run on every freeze in production?** It is O(steps), not
   O(samples). Recommend yes, off the timed path, and measure it in 0127.
2. **Throw or return at freeze?** Recommend throw: a broken invariant at freeze
   is an engine bug, and returning it would put the bug on the report as though
   it were a fact about the target.
3. **Is invariant 8 checkable at all** with only frozen histograms? Not per
   sample — only the counts and the ordering are. Recommend stating it as a
   recorder-level property tested in 0124, and checking the weaker count and
   ordering form here.
4. **Does making the counts nullable break the published API?** Yes, and
   `apiDump` will show it. Recommend absorbing it before 1.0 rather than after.
