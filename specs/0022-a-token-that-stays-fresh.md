# 0022 — A token that stays fresh

## Problem

A run longer than a token's lifetime cannot authenticate. Half an hour into a
soak the bearer token expires, every request turns into a 401, and the report
says the target started failing — which it did not.

The workaround people reach for makes it worse. Refreshing inside a step means
the refresh call is timed as that step, so one request in a few hundred carries
the cost of a round trip to an identity provider and the p99 is a measurement
of the auth server. This is the Gatling complaint stated exactly: feeding an
updated token through a running test is awkward there, and the awkward answers
all corrupt the numbers.

Per-user tokens already work: a login step captures into the session, and every
later step reads it. What is missing is the shared credential that has to stay
fresh for the whole run.

## Not doing

- No OAuth client, no device flow, no identity provider integration. Proofload
  gets a token from a function the caller wrote.
- No retry-on-401. Retries change what is being measured and belong in their
  own spec.
- No credential storage, no secrets handling. A token comes from the caller's
  environment, and Proofload neither reads nor writes it anywhere.
- No per-user refresh. Per-user credentials are the session's job.

## Shape

```kotlin
import io.github.matthewjones372.proofload.refreshing
import kotlin.time.Duration.Companion.minutes

val token = refreshing(every = 5.minutes) { fetchToken() }

val checkout = scenario("checkout") {
    exec(browse, api.get("/products").header("authorization", "Bearer ${token.current}"))
}
```

- `Refreshing<T>` — a value holding the current one, replaced on a schedule by
  a thread that is not running any step.
- `refreshing(every, fetch)` — starts cold, so the first fetch happens before
  the run rather than inside the first request that needs it.
- `current` reads a volatile field. No lock, no wait, no allocation.

## Why this shape

The refresh must not happen on the path being measured. That is the whole
point: a step reads a field, and the field is replaced by something else,
somewhere else. A caller who refreshes inside a step is timing their identity
provider and will not notice.

A value rather than a callback so the scenario reads normally, and so a test
can hand a fixed token in with no scheduler at all.

Cold start rather than lazy: a lazy first fetch lands inside whichever virtual
user gets there first, which is exactly the thing this spec exists to stop.

## Stack

- [x] **`spec-0022-refreshing`** — `Refreshing`, `refreshing(every, fetch)`,
      its scheduler and its shutdown.
      Done when: `current` never blocks, a refresh replaces the value without a
      step seeing a gap, and a run that ends stops the refresher.
- [x] **`spec-0022-failure`** — what happens when a refresh throws.
      Done when: a failed refresh keeps the last good value, is counted, and is
      reported on the page rather than swallowed.
      Half built: a failed refresh no longer cancels the schedule, keeps the
      last good value, and is counted and named on `Refreshing` itself. It is
      **not** on the page — nothing connects a `Refreshing` to a `RunResult`,
      and the wiring that would (a credential named on the `Simulation`, a
      field on the result, a row on the report) is API this spec never argued
      for. It wants a spec of its own; see open question 4.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

Answered by the architect:

1. **A failed refresh keeps the stale value and tries again next interval.**
    Emptying it would turn one identity-provider blip into a run-wide outage
    that looks like the target's fault.
2. **The refresher is a daemon thread**, so a `main` that forgets to stop it
    still exits.
3. **Refresh failures appear in the report** as a run-level note, not as step
    failures. They are the tool's problem, and burying them in a step's failure
    counts would misattribute them to the target.
4. **How does a stale credential reach the page?** Nothing joins a
    `Refreshing` to a run: it is a value the caller holds and the scenario
    closes over, so the engine never sees it. Recommend a spec of its own —
    a credential named on the `Simulation`, carried into `RunResult`, and a
    line on the report where the run failed to refresh — rather than smuggling
    the API in here. Until then `failures` and `lastFailure` are assertable in
    the test that owns the run.
