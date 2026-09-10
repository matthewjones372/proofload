# 0134 — The statements that must never become false

## Problem

Proofload's claim is that its measurements can be trusted. That claim is
currently spread across sixty specs, a README paragraph, `docs/concepts.md` and
a great many KDoc comments, which means it is defended by reading rather than by
running. There is no single list of the things that must hold, so there is no
way to ask "did this change break the claim?" of a diff.

This spec is that list. It is deliberately short: an invariant here is something
whose falsity would make a Proofload run untrustworthy, not something that would
merely be a bug. Each is derived from the repository — from what the engine
already does, from what the specs already argue, and in three cases from what
this review found the tool cannot currently say.

## Not doing

- **No implementation.** Every clause points at the spec that owns it.
- Nothing about performance, ergonomics, formats or coverage. Those are quality;
  these are correctness of measurement.
- No invariant that is merely a good idea. Sixteen is already at the edge of
  what anyone will hold in their head.

## The invariants

**Arrivals**

1. An open-model arrival schedule is a pure function of the profile and the
   arrival's index. No response, no measurement and no clock reading may change
   when a future arrival is due. *(0130, 0122; holds today, structurally.)*
2. Every arrival the profile named is either sent, or counted as not sent. An
   arrival never silently disappears. *(0122, 0128; holds today for missed,
   not yet for dropped.)*
3. Booking is never paced or smoothed. A backlog is discharged immediately and
   recorded as lateness. *(0122; holds today.)*

**Lateness**

4. Generator lateness is observable on every open run, per second and in
   aggregate. *(0076, 0097; holds today.)*
5. Generator backlog is never reported as target latency. `responseTime =
   serviceTime + lateness`, and lateness is never folded into service time nor
   subtracted from response time. *(0003, 0122; holds today at departure.)*
6. Load-generator saturation, JVM execution saturation and target saturation are
   distinguishable in the result. *(0126; **does not hold today** — carrier
   starvation inside a step is reported as the target's service time.)*

**Validity**

7. A percentile from an invalid measurement cannot silently be treated as
   trustworthy. Reading one requires acknowledging the run's validity, and the
   short path is the safe one. *(0121; **does not hold today** — the README's
   own example is the counter-example.)*
8. A run that was asked nothing has met nothing. *(0121; **does not hold
   today** — `metEveryGoal` is true for a goal-less run.)*
9. A run that sent nothing is never valid, and never meets a goal. *(0121,
   0130; **does not hold today** — a zero-arrival run meets a failure-rate
   goal.)*
10. A closed-model run is never presented as an open-model measurement, and its
    response time is never presented as a second clock. *(0131; partly holds.)*

**Results**

11. Every recorded observation is attributable to exactly one step, exactly
    once. *(0129, 0125; holds today, untested under load.)*
12. Concurrent aggregation loses no observation and invents none. *(0125,
    0129.)*
13. Unmeasured is distinguishable from zero, everywhere a number is reported.
    *(0129; partly holds — `reached`, `visits`, `attempts` and `produced` use
    zero for both.)*
14. Nothing is estimated, smoothed or interpolated. A percentile is the top of a
    counted bucket, and every number carries the width it was counted at.
    *(0003, 0047; holds today.)*

**Isolation and lifecycle**

15. User and session state is isolated between concurrent virtual users. A
    user's data is a function of its own number. *(0015, 0125; holds by
    construction, untested at scale.)*
16. Cancellation cannot produce a result indistinguishable from a complete run,
    and a run always produces a result or produces nothing — never a partial one
    presented as whole. *(0128; **does not hold today** — there is no
    cancellation, and a JVM exit loses everything.)*

## How each is defended

An invariant with no executable defence is a sentence, which is the thing this
spec exists to replace. Each must end up with at least one of:

- a **structural** defence — it cannot be written wrong, e.g. 1, where
  `departures()` takes no argument that could carry a response;
- a **property test** with no clock in it (0124);
- an **adversarial experiment** against an oracle (0123);
- a **stress check** at scale (0125);
- a **result check** run at every freeze and merge (0129).

The table below is the deliverable, not the prose above it.

| # | Holds today | Defence | Owner |
|---|---|---|---|
| 1 | yes | structural + property | 0130, 0124 |
| 2 | partly | property + lifecycle test | 0122, 0128 |
| 3 | yes | property | 0124 |
| 4 | yes | property + timing | 0122 |
| 5 | at departure only | property + oracle | 0122, 0123 |
| 6 | **no** | probe + adversarial | 0126, 0123 |
| 7 | **no** | API shape + every example | 0121 |
| 8 | **no** | unit | 0121 |
| 9 | **no** | unit + construction refusal | 0121, 0130 |
| 10 | partly | unit + adversarial | 0131, 0123 |
| 11 | yes, untested | stress ledger | 0125 |
| 12 | yes, untested | stress ledger | 0125 |
| 13 | partly | result check | 0129 |
| 14 | yes | property | 0124 |
| 15 | yes, untested | stress | 0125 |
| 16 | **no** | lifecycle test | 0128 |

Five of sixteen do not hold. That number, and not any sentence in the README, is
the honest statement of where 1.0 stands.

## Stack

- [x] **`spec-0134-list`** — the invariants as `docs/invariants.md`, each with
      its number, its owner spec, and its current status.
      Done when: the page exists, is linked from the README and `llms.txt`, and
      every row's status matches the tree.
      #89. `llms.txt` was at its hundred-line ceiling, so the link cost a line;
      the Cookbook entry was the one entry wrapped over two and is now one,
      which is no content and makes it match its neighbours.
- [x] **`spec-0134-citations`** — each defending test names its invariant in its
      own name or KDoc, and a task checks every invariant has at least one
      citation.
      Done when: `./gradlew invariants` lists sixteen rows and fails on one with
      no test behind it.
      #93. Sixteen rows, yes. "Fails on one with no test behind it" is a
      ratchet rather than the literal rule: read literally it fails the day it
      lands, because nine have no citing test and five of those describe
      behaviour the tool does not have. It fails when one of the seven that
      *are* defended loses its last test. `Invariants.REQUIRED` is that set,
      and adding to it is how an invariant becomes gated.
- [x] **`spec-0134-gate`** — the check runs from `check`, so an invariant losing
      its last test fails the build.
      Done when: deleting a defending test turns the build red with the
      invariant's number in the message.
      #93. Verified by removing `LatenessTableTest`'s citation: the build fails
      with "no test defends invariants 4 and 5", while 3 stays defended by the
      two other tests that cite it.

## Acceptance

```bash
./gradlew invariants
./gradlew build
```

## Open questions

1. **Is sixteen too many?** Recommend keeping all sixteen and resisting the
   seventeenth. Each of these fails a real run in a way a reader could not spot.
2. **Should an invariant be assertable at runtime**, so a run refuses to freeze
   a result that breaks one? Recommend for 11, 12 and 13 via 0129's `check()`,
   and against for the rest: 1 and 15 are structural, and 6, 7 and 16 are about
   what a *caller* is allowed to conclude, not about the value.
3. **What is the release rule?** Recommend: 1.0 does not ship with a "no" in the
   table. If any is unfixable in time, the honest alternative is to weaken the
   README's claim to match rather than to ship the claim and the gap.
4. **Who owns the table after 1.0?** Recommend the generated ROADMAP task from
   0133 also regenerates this table's status column, so it cannot drift.
