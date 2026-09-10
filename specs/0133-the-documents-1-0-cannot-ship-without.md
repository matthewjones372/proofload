# 0133 — The documents 1.0 cannot ship without

## Problem

Proofload's central claim is a documentation claim as much as a code one: the
README says the tool "proves on every run whether the generator kept up", and a
reader has no way to check that except by reading it. An audit of the pages
against the code turns up four kinds of drift, and the first kind is the one
that matters.

**Claims stronger than the implementation.**

- *"Proves on every run whether the generator kept up. Green means the numbers
  are the target's, not the tool's."* The proof exists and is not on the path a
  caller takes: the README's own first example asserts
  `result[placeOrder].responseTime.p99 < 200.milliseconds` with no schedule
  check, `metEveryGoal` is true for a run with no goals, and `Headline.Behind` —
  the only written order of precedence — is `private` in `proofload-export`.
  0121.
- *"Load testing with a p99 you can trust."* Not true when the generator's own
  carrier pool is the bottleneck: that wait is inside `serviceTime` and every
  gate stays green. 0126.
- *"the closed model, labelled with what it cannot see"* (`ROADMAP.md`). The
  label exists on the page and not in the value: a closed run passes every
  schedule gate by construction and its JSON headline reads `met`. 0131.
- `docs/concepts.md`'s "Where you meet it" table names **`schedule.kept`** as
  though it were Kotlin. It is a JSON field. There is no `RunResult.schedule`.

**Claims weaker than the implementation, or missing.**

- Nothing states the open-model arithmetic — count, offsets, boundaries,
  rounding, sharding — although all of it is exact and load-bearing. 0130.
- Nothing states what a step measures. That user code inside a step body is in
  the number is a surprise, and it is undocumented. 0132.
- `Offered`, `heldScheduleFor`, `latePerSecond` and `ranOutOfRoom()` are built
  and appear in no user-facing page.
- The two-clock diagram is excellent and appears once, in `docs/concepts.md`.
  The README implies it and never shows it.

**Undefined or double-defined terminology.** "Behind", "late", "lost ground",
"void", "backlog", "in flight" and "departure" each carry two meanings across
the specs, the KDoc and `docs/`. 0122 fixes the vocabulary; this spec is what
makes the pages use it.

**Assumptions living only in code.** That arrivals are `[0, over)`. That
truncation drops a trailing fractional user. That `schedulingDelay` is the
*user's* departure lateness and is added to every step of that user's journey,
including the fifth. That `arrivals` records the *intended* offsets. That a
retry reports the last attempt. That `0` means "not measured" for `reached`,
`visits`, `attempts` and `produced`. Each is a comment; none is a page.

**Contradictions.** `ROADMAP.md` and `specs/README.md` both carry notes saying
they went stale and were reconciled — twice. 0097's stack entry
`spec-0097-because` is ticked and promised `result.schedule.because`, which does
not exist; `scheduleRemedy` is what landed. A ticked box that names an API the
tree does not have is the failure mode both notes describe, recurring.

## Not doing

- **No rewriting of the documentation in this spec.** This says which documents
  must exist and what each must contain, so the writing can be reviewed against
  a list rather than against taste.
- No new documentation *system*. Markdown in `docs/`, as now.
- No versioned docs site.
- Not reconciling `ROADMAP.md` a third time by hand — see the stack.

## What must exist before 1.0

Seven documents. Four are new, three are corrections.

1. **`docs/open-model.md`** (new, 0130). The arithmetic as a contract: count,
   offsets, half-open boundaries, fractional rates, randomisation, mixes,
   sharding, rounding. Every clause carries a test reference.
2. **`docs/what-a-step-measures.md`** (new, 0132). Where the clock starts and
   stops, what is inside, what is beside, the `emit` exception, and the
   surprising cases spelled out.
3. **`docs/validity.md`** (new, 0121). What makes a run valid, partial or
   invalid; which reads are safe under each; the precedence order — moved out of
   `RunJson` and stated once.
4. **`docs/what-proofload-does-not-claim.md`** (new, 0126, 0131). The virtual-thread
   claims and non-claims; the closed-model statement; what a single run's
   interval does not bound; what a socket ceiling cannot say (0118–0120).
5. **README** (correction). The headline example rewritten onto whatever 0121
   lands, so the first code a reader sees is the safe form. The "proves on every
   run" sentence made exact.
6. **`docs/concepts.md`** (correction). One vocabulary, 0122's; `schedule.kept`
   named as JSON or replaced with the Kotlin; `Offered`, `heldScheduleFor` and
   `ranOutOfRoom` given entries.
7. **`llms.txt` and `docs/for-agents.md`** (correction). Both are read by
   something that cannot ask a follow-up question, so the validity precedence
   and the non-claims belong there first, not last.

## Acceptance criteria

- **No page claims something no test holds.** Each of the four new documents
  ends with a table of claim → test, and a claim with no test says so in the
  row rather than being dropped.
- Every term in 0122's vocabulary appears in exactly one document as its
  definition, and every other use links it.
- No example in the repository — README, `docs/`, `examples/`, KDoc — reads a
  percentile off a result whose validity was not established.
- `docs/modules.md`'s rule extends here: a dependency claim is a test, and after
  this a **semantic** claim is a test too, or it is marked untested.
- `ROADMAP.md` is generated from the specs' stack boxes rather than maintained,
  so it cannot go stale a third time.

## Stack

- [ ] **`spec-0133-audit`** — the audit above as `docs/AUDIT.md`, one row per
      claim, with its status and the spec that fixes it.
      Done when: every claim in the README, `docs/concepts.md` and `llms.txt` is
      a row marked held, weakened, or owned by a spec.
- [ ] **`spec-0133-roadmap`** — `ROADMAP.md` generated from the stack boxes, and
      0097's ticked-but-absent entry corrected.
      Done when: the file is produced by a task, a stale box fails the build,
      and `spec-0097-because` names `scheduleRemedy` or is unticked.
- [ ] **`spec-0133-vocabulary`** — 0122's terms into `docs/concepts.md`, and the
      duplicate meanings removed from KDoc.
      Done when: no page uses "behind" for two things.
- [ ] **`spec-0133-new-pages`** — documents 1 to 4, each landing with the spec
      that owns it rather than in one commit.
      **Not this spec's work.** Every one of the four is already a stack entry
      elsewhere — `spec-0130-contract`, `spec-0131-statement`,
      `spec-0132-contract` and `spec-0126-claims` — so what is left here is the
      linking, and that is one line in each of those. Kept as a checklist of
      what must exist before 1.0, built nowhere but there.
      Done when: each exists, is linked from the README's documentation list and
      from `llms.txt`, and ends with its claim-to-test table.

## Acceptance

```bash
./gradlew build          # the docs tests, and the generated ROADMAP check
```

## Open questions

1. **Can a "claim → test" table be checked mechanically?** Partly: a row citing
   a test name that does not exist can fail the build, the way `docs/modules.md`
   already works. Recommend that much and no more; whether a test proves its
   claim stays a human's judgement.
2. **Should the README lead with the limitation?** Recommend no — lead with the
   claim, and make the first example the safe one. A README that opens with
   caveats is one nobody finishes.
3. **Is a generated `ROADMAP.md` worth it?** It has gone stale twice and been
   apologised for twice in the tree. Recommend yes.
4. **Does `docs/AUDIT.md` survive 1.0?** Recommend it becomes the claim-to-test
   index and stays, rather than being deleted once the rows go green.
