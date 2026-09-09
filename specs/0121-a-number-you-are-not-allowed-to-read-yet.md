# 0121 — A number you are not allowed to read yet

## Problem

The README's own first example is the thing this tool exists to stop:

```kotlin
assertTrue(result[placeOrder].responseTime.p99 < 200.milliseconds)
```

Nothing in that line acknowledges that the generator kept its schedule. The
protections all exist and none of them is on the path a caller takes.
`fellBehind()` is a free function nobody has to call; `keptSchedule` is a goal
nobody has to declare; `Headline.Behind` — the one place an order of precedence
is written down — is `private` in `proofload-export` and unreachable from a
test. Core's own `metEveryGoal` returns **true for a run with no goals**, and
`FailureRateUnder` is met by a run that sent nothing, because zero of zero is
zero percent.

So a run can be worthless in four different ways and still hand a green
assertion to a build:

- **Behind.** Mitigated for `responseTime`, which carries the lateness — but
  `serviceTime` is a public sibling with the lateness removed and no gate on it,
  and it is the clock the report shows by default.
- **Empty.** `100.perSecond over 250.milliseconds` is 25 users; `1.perSecond
  over 500.milliseconds` is **zero**, and a zero-request run meets every
  failure-rate goal it was set.
- **Closed.** `plan.closed` runs record no lateness by construction, so every
  schedule gate is false, `offered` is null and the headline is `Met`.
- **Void by the injector's own limits.** `ranOutOfRoom()` voids a search rung
  and says nothing about a plain run.

## Not doing

- **No new measurement.** Every input below is already on `RunResult`.
- **No change to the two clocks, to `fellBehind`, to `lostGround` or to
  `MATERIAL`.** 0043 and 0097 settled those and this reads them.
- No refusal to run, and no exception on reading a percentile. A tail from a
  behind run is still the evidence of what went wrong.
- Not a fifth verdict type. `Verdict` judges goals; this judges the run.

## Shape

Validity in core, as a value, and the existing gates as its reasons.

```kotlin
result.validity                      // Validity.Invalid(listOf(FellBehind(41.ms, 3.2.ms)))
result.validity.trustworthy          // false
result[placeOrder].responseTime.p99  // still readable, and still 241 ms

// the assertion that cannot be written by accident:
result.trusted()[placeOrder].responseTime.p99 shouldBeLessThan 200.milliseconds
```

- `sealed interface Validity { Valid; Partial(List<Doubt>); Invalid(List<Doubt>) }`
  and `sealed interface Doubt` with one case per gate that already exists:
  `FellBehind`, `LostGround`, `RanOutOfRoom`, `NothingSent`, `ClosedModel`,
  `LawDisagrees`, `Cancelled` (0128), `CarrierStarved` (0126). Each carries the
  numbers that decided it — never a rendered string.
- **Invalid** — the schedule was not kept (`lostGround`), the injector hit its
  own ceiling, nothing was sent, or the run did not complete. Nothing about the
  target was measured at the load that was asked for.
- **Partial** — the run measured *something* smaller than what was asked:
  `fellBehind` without `lostGround`, a closed run, a segment that never settled.
  `offered` names what the smaller experiment was. Service time is a true
  measurement at `offered.left`; response time is not a measurement of the
  target at all.
- **Valid** — no doubt fired, and at least one sample was recorded.
- Per **step**, not only per run: `StepStats.validity`, because `fellBehind`
  weighs lateness against the *worst* step's tail. In a mix of a 2 s step and a
  2 ms step, 50 ms of lateness is immaterial to the first and 25× the second,
  and one run-level boolean says the run is fine.
- `metEveryGoal` becomes false where `validity` is not `Valid`, and false for a
  run with no goals — `Headline.NothingAsked` moved into core, where the value
  a test reads lives.

## Why this shape

Four ways to make misuse hard, in increasing cost:

1. **Documentation.** Free, and already tried: the README says the verdict
   outranks the goals, and the README's own example ignores it.
2. **A flag on the result.** `result.validity` beside the percentiles. Honest,
   cheap, and still opt-in — the accidental read stays legal.
3. **A wrapper the percentiles live behind.** `result.trusted()` returns the
   steps, or throws with the doubts named; `result.evidence()` returns them
   unconditionally for the caller diagnosing the failure. Nothing is deleted,
   so no existing test breaks and no report loses its numbers.
4. **A type that cannot be dereferenced at all until validity is matched** —
   `Either`-shaped, percentiles unreachable from `RunResult`. This is correct
   and it makes reading a tail from a broken run — the most common real
   debugging need — a fight with the type system.

**Recommend 2 and 3 together.** 2 makes the fact exist as a value; 3 makes the
short path the safe one, and leaves the escape hatch named after what it is.
4 is recommended against, on the strength of the debugging case.

## Adversarial cases

Each of these is green today and must not be after this lands.

| Run | Reads | Should say |
|---|---|---|
| `1.perSecond over 500.ms`, no requests | `failureRate under 1.percent` met | `Invalid(NothingSent)` |
| any run, no goals | `metEveryGoal == true` | false, `NothingAsked` |
| closed 50 users | headline `met`, `behind` empty | `Partial(ClosedModel)` |
| mix: 2 s step + 2 ms step, 50 ms late | `fellBehind() == false` | fast step `Invalid` |
| lateness 41 ms, tail 356 ms, timeline flat | `Partial`, not `Invalid` (0097) | unchanged |

## Stack

- [ ] **`spec-0121-validity`** — `Validity`, `Doubt` and `RunResult.validity` in
      core, derived from the existing gates. No new field is stored.
      Done when: each row of the table above reads the validity named, and a
      run that kept its schedule reads `Valid`.
- [ ] **`spec-0121-per-step`** — `StepStats.validity`, lateness weighed against
      each step's own tail rather than the worst.
      Done when: the mix row reports the fast step invalid and the slow step
      valid, and a single-step run agrees with `RunResult.validity`.
- [ ] **`spec-0121-goals`** — `metEveryGoal` false where validity is not `Valid`
      or where no goal was declared; `NothingAsked` moved into core and read by
      `RunJson`'s headline rather than computed there.
      Done when: the golden JSON is unchanged and the export module computes no
      headline of its own.
- [ ] **`spec-0121-trusted`** — `trusted()` and `evidence()`, and the README and
      cookbook examples rewritten onto `trusted()`.
      Done when: `trusted()` on a behind run throws naming the doubts and their
      numbers, `evidence()` on the same run returns the steps, and no example in
      the repository reads a percentile off an unchecked result.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Does `trusted()` throw or return a nullable?** Recommend throwing: this is
   a test assertion path, the message is the finding, and a null would be
   silently `?.let`-ed away.
2. **Is a closed run `Partial` or `Valid`?** Recommend `Partial`. Its service
   times are real and its response times are the same number, which is the
   omission — a run that cannot see its own backlog has not earned `Valid`.
3. **Does `Partial` fail a build?** Recommend not by itself: `metEveryGoal`
   already goes false on the doubts that are `Invalid`, and a `Partial` run with
   every goal met is a smaller experiment that passed.
4. **Where does `NothingSent`'s threshold sit — zero samples, or fewer than the
   plan promised?** Recommend zero for `Invalid` and a shortfall past
   `Offered.share` for a `Partial`, so a run that sent 3 of 3,000 is not `Valid`.
