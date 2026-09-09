# 0125 — Correct at a hundred thousand

## Problem

`Recorders` is the most delicate object in the repository and the least
adversarially tested. It claims: one recorder per carrier, claimed by
compare-and-set, uncontended because nothing on the path blocks, "correct
rather than merely lucky if one ever does". Each clause is a claim about
behaviour under concurrency, and the tests behind them run ten users.

Three specific things are unproven and one is wrong.

**Unproven.** That no sample is lost or duplicated when every carrier is
writing. That `counted`/`failures` — written with `lazySet` by whichever thread
holds a slot — add up to the frozen histograms' counts. That a step body which
retains the `SampleSink` it was handed (it is the `UserWalk` itself, and the
body gets it) and records from another thread cannot corrupt a slot; today it
either lands in a recorder that is about to be merged, or arrives after freeze
and hits `checkNotNull(...) { "a shard was still in use when the run ended" }`,
which destroys the whole run's result.

**Wrong.** `Departed.left` does `count += 1` on a `@Volatile var` from the
scheduler thread — single-writer, so correct — *except* on the closed path,
where `goRound` calls it from every user's virtual thread on every lap. That is
a lost-update race, and its symptom is a `Snapshot.departed` quietly low rather
than a crash. It is a progress number, not a frozen one, which is why it is P1
and not P0 — but it is a number the tool prints.

## Not doing

- **No performance target.** Correctness only. Whether 100,000 users is fast is
  0011's and 0093's question; whether it is *right* is this one.
- No change to the sharded-recorder design. It is the correct shape and this
  spec tests it.
- No unbounded-scale requirement. The scale is a benchmark parameter: 100 and
  1,000 run in `build`; 10,000 and 100,000 are a task run on request, because a
  100,000-user run needs descriptors and heap a CI runner may not have.
- No new synchronisation on the timed path. A fix that costs an allocation or a
  fence per request is worse than the bug.

## What correctness means here

Independent of speed, and each checkable:

1. **No lost samples.** `sum over steps of count` equals the number of times a
   body was run, counted by the bodies themselves in an `AtomicLong` outside the
   recorder.
2. **No duplicates.** The same, from the other side: each user records a value
   unique to it, and the union of the frozen buckets holds each exactly once.
3. **Counts agree.** `RunResult.count`, `ok + failed`, each step's `ok.count +
   failed.count`, `sum(timeline.count)` and the reason-map totals are equal.
4. **Session isolation.** User *n*'s session holds only what user *n*'s feeder
   and captures put there. Asserted by feeding each user its own number and
   checking every later step reads its own back.
5. **Step identity.** Every sample lands under the step that produced it, with
   `reached` counting users once and `visits` counting bodies once, under
   `repeat`, `during` and `doIf`.
6. **Failures.** A failed step abandons its user, records under its own name,
   and the steps after it record nothing — under concurrency, not just in a
   single-user test.
7. **Bounded memory.** Live set flat in the number of users, which 0093 measures
   and this asserts does not regress at 10× the users.
8. **Termination.** The run ends. No deadlock, no user starved of a carrier
   forever, no `awaitAll` that returns before a booked user has run.

## Adversarial shapes

- **All-fail.** Every step fails: the failure path allocates a `Reason` and
  touches a `LinkedHashMap` under `MAX_REASONS_PER_STEP`. At 100,000 users with
  a distinct reason each, the map must cap at 20 and fold the rest into `Other`
  without losing a count.
- **All-succeed, one step, maximum rate** — the pure recorder-contention case.
- **Mixed, 10 arms** — 10 recorders' worth of step names in every slot.
- **A body that records from another thread**, after its own step returned.
- **A body that records after the run ends.** Today: an exception at freeze that
  loses everything. Must become a named doubt (0121) or a refusal at the seam.
- **A body that throws `Error`** — `attempt` catches `Throwable`, so an
  `OutOfMemoryError` in one user is recorded as a failed request. That is either
  right or the worst possible reading of the run, and it is undecided.
- **Cancellation mid-flight** — 0128.

## Shape

```bash
./gradlew :benchmarks:concurrency            # 100, 1_000
./gradlew :benchmarks:concurrency -Pusers=100000
```

A harness, not a test lane: it runs a scenario whose body is arithmetic, counts
independently of the recorder, and reports a table of the eight checks. The
100- and 1,000-user rows also run as ordinary tests in `build`, because a
correctness claim nobody runs by default is not one.

## Stack

- [ ] **`spec-0125-ledger`** — the independent counter and checks 1–3 as tests
      at 100 and 1,000 users.
      Done when: every count agrees exactly, and a deliberately racy recorder
      substituted in the same test source fails at least one of them.
- [ ] **`spec-0125-isolation`** — checks 4, 5 and 6 under a scenario with a
      loop, a condition and a failing branch.
      Done when: no user reads another's session at 1,000 users, `reached` is
      the user count and `visits` is the iteration count.
- [ ] **`spec-0125-departed`** — the closed-path `Departed` race.
      Done when: a 1,000-user closed run's `Snapshot.departed` equals the laps
      the bodies counted, and the fix costs the open path nothing measurable in
      `:benchmarks:ceiling`.
- [ ] **`spec-0125-late-samples`** — a sample recorded after the run ends is a
      named outcome rather than a `checkNotNull` that loses the run.
      Done when: the late sample is either refused where it is written or
      counted as a doubt, and the other 99,999 users' samples survive.
- [ ] **`spec-0125-scale`** — the `concurrency` benchmark task, at 10,000 and
      100,000, excluded from coverage and from `build`.
      Done when: the table prints all eight checks and the live set at 100,000
      is within 0093's stated bound.

## Acceptance

```bash
./gradlew build
./gradlew :benchmarks:concurrency -Pusers=10000
```

## Open questions

1. **Is `Error` out of a step body a failed request or a broken run?**
   Recommend: `Exception` is a failed request, `Error` re-thrown and the run
   marked `Invalid`. An `OutOfMemoryError` counted as a 500 is the tool
   reporting its own death as the target's.
2. **What is the honest ceiling for `users(100_000)`?** Recommend measuring
   rather than asserting, and printing the descriptor and heap requirement in
   the table so the number is reproducible.
3. **Should the racy-recorder control live in the tree?** Recommend yes, for
   0123's reason: a check that cannot fail proves nothing.
4. **Does the independent counter distort the measurement?** It is one atomic
   per request on the timed path. Recommend running the ledger checks only in
   the concurrency harness, never in a run anybody quotes.
