# 0132 — Where a step starts and stops

## Problem

A step's service time is the wall clock across `Action.attempt`, and nothing
says so anywhere a user reads. The consequence is that two scenarios which look
equivalent measure different things, and the difference is invisible:

```kotlin
exec(price) { api.get("/price").run(scope) }                    // the call
exec(price) { api.get("/price").run(scope); recompute(scope) }  // the call plus your work
```

The second reports a slower target. The user wrote a step, not a stopwatch, and
has no way to know that `recompute` is inside the number — or that a check, a
capture, a JSON parse, a redirect walk and a retry are too.

The current boundaries, read off `UserWalk.runOn`:

- **Excluded before:** the session, the `StepScope` allocation, the `notes` list.
  The clock starts after the scope exists.
- **Included:** everything the body does. Request construction, the send, the
  wait, the response read, `expecting(...)` checks, `capture(...)` extraction,
  redirects (0055), retries (0026 — deliberately, and the attempt count sits
  beside it), user code, and any nested step body.
- **Excluded after:** `sink.record`, `sink.aside`, the reason mapping, the
  narration.
- **Beside, not inside:** `queued` — time waiting for a generator-owned resource
  such as a JDBC connection (0083) — and `behind`, the departure lateness.

Every one of those is a defensible choice. None is written down, none is tested
as a boundary, and two of them are surprising: user code is included, and the
retry case reports the *last* attempt's latency while `attempts` counts the
round trips.

## Not doing

- **No change to the boundaries.** This spec makes them explicit and testable.
  The one open question is whether a caller should be able to narrow them, and
  the recommendation is a narrow addition, not a change of default.
- No nested-step timing tree. A step is one measurement; a scenario is not a
  span tree, and 0023's traces are where that question belongs.
- No automatic exclusion of user code. The engine cannot tell a user's
  `recompute` from a user's own HTTP client.

## The semantics

**Measurement starts** at the first instruction of the step body, after the
engine has built everything the body needs. Preparation the *engine* does is
excluded; preparation the *body* does is included, because the engine cannot see
where it ends.

**Measurement stops** when the body returns or throws. A throw is a failed
request whose latency is the time until the throw.

**One visit is one measurement**, unless the body records its own samples
through the scope (0075), in which case the engine records none for the whole
body and each sample is timed by whoever reported it.

**Nested constructs** — `repeat`, `during`, `when` — are iterations, not steps.
They are not timed; the `exec` steps inside them are, once each. So `count` can
exceed `reached` for a loop and `visits` tells a loop from a stream.

**A `pause` is not a step.** It records nothing, it is not latency, and it does
not enter the following step's service time.

**Retries and redirects** are one measurement over all attempts, with `attempts`
counting the round trips beside it. A retry folded into one sample reports the
target as slower than it is; splitting it into several would report a request
the user never made.

## Examples, and what each measures

```kotlin
exec(price) { client.call() }
// service = the call

exec(price) { client.call(); expensiveLocalComputation() }
// service = call + computation.  Surprising, and now documented.

exec(price, api.get("/price").expecting(200).capture(id) { it.header("x") })
// service = send + wait + read + status check + header extraction

exec(price) { pool.connection().use { it.query() } }        // via proofload-jdbc
// service = the query;  queued = the connection checkout, beside it

scenario { pause(2.seconds); exec(price, api.get("/price")) }
// service = the call.  The pause is in neither clock and in no histogram.

exec(price, api.get("/price").retrying(3))
// service = the last attempt;  attempts = 3
```

## Adversarial cases

- **A body that captures the `SampleSink` and records after returning.** The
  sample lands under a step that is no longer running, or after the run ends
  (0125). Must be refused or named, never silently attributed.
- **A body that starts a thread and does not join it.** Its work is outside the
  measurement and the request is reported as fast. Nothing can stop this; the
  documentation must say it.
- **A body whose first line is an allocation storm.** Included, correctly, and
  indistinguishable from the target being slow — which is the general form of
  the "user code is included" rule and the reason it must be documented rather
  than hidden.
- **A step that throws before sending anything.** Latency near zero, counted as
  a failure. Correct, and it drags the failed distribution down — which is
  exactly why `Outcome` keeps the two sides apart (0036).
- **A carrier lost mid-body** — included in service time, and 0126's problem.
- **`emit` (0040).** The publish is timed like any other step; the answer's
  latency is measured from the *promised departure* and has no service time of
  its own. Two different boundaries under one scenario, and the only place the
  rule above does not hold.

## Acceptance criteria

- A step whose body sleeps *n* ms on top of a 1 ms call reports ≈ *n*+1 ms, and
  the assertion cites this spec.
- A step wrapped in `repeat(3)` reports `count` 3, `visits` 3, `reached` 1.
- A streaming step reporting 100 samples reports `count` 100, `visits` 1,
  `reached` 1, `streamed` true.
- A JDBC step against a 1-connection pool with 10 users reports a `queued`
  distribution and a service time that does not contain it.
- A `pause` of 2 s between two steps changes neither step's service time and
  adds nothing to `behind`.
- A step that throws is one failed sample with the latency up to the throw.
- The engine's own bookkeeping is outside: a step body that does nothing reports
  a service time within the machine floor (0039) of zero.

## Stack

- [ ] **`spec-0132-contract`** — the semantics and the example table as
      `docs/what-a-step-measures.md`, linked from `docs/concepts.md`, the
      cookbook and `llms.txt`.
      Done when: every clause has a test citing it.
- [ ] **`spec-0132-boundaries`** — the acceptance criteria as tests, in the
      deterministic lane where they can be and `timing` where they cannot.
      Done when: each bullet is an assertion and the empty-body one is stated
      against the measured floor rather than against a constant.
- [ ] **`spec-0132-emit`** — the `emit` exception written down beside the rule.
      Done when: `docs/concepts.md` states that a completion's latency has one
      clock, and the page says so where it prints one.

## Open questions

1. **Should a body be able to narrow the measurement** — a `measuring { ... }`
   inside `exec`? Recommend adding it later, if at all: it lets a caller exclude
   their own work honestly, and it also lets them exclude the slow part of the
   target. The safer half is `scope.sample(...)`, which already exists.
2. **Is including user code the right default?** Recommend yes. The alternative
   requires the engine to know which call was the target's, which is exactly the
   knowledge a `step { }` body exists to avoid needing.
3. **Should `attempts > 1` be a doubt?** Recommend no, a footnote: a retried
   request is one the user really did wait for.
