# 0026 — Checks and retries

## Problem

A request that returns 200 with an error page in the body is counted as a
success. The only thing `kestrel-http` checks is the status, so a target that
degrades into cheerful empty responses looks faster and healthier than one that
fails honestly.

Retries are the other half. Every real client retries something — a 503, a
timeout, a token that expired — and Kestrel has no way to express it. Worse, a
caller who writes their own retry loop inside a step gets the retries timed as
one long request, which quietly triples a p99 nobody can explain.

## Not doing

- No response body DSL, no JSONPath, no XPath. A check is a Kotlin function
  over the response.
- No schema validation. Pelican already holds the contract for anyone who wants
  that.
- No retry-by-default. A retry changes what is measured and has to be asked
  for.
- No circuit breaking, no hedging.

## Shape

```kotlin
exec(
    placeOrder,
    api.post("/orders")
        .expecting(201)
        .checking("has an id") { response -> "\"id\"" in response.text() }
        .retrying(twice, on = { it.status == 503 }),
)
```

- `checking(name) { response -> Boolean }` — a failed check fails the step with
  the check's name as the reason, so a report says `has an id` rather than
  `status 200`.
- `retrying(times, on)` — each attempt is **recorded separately**, and the step
  is counted once with the outcome of the last attempt.
- A retried step reports its attempts: `/pay` 480 requests, 512 attempts.

## Why this shape

Recording every attempt is the decision that matters. A retry that is folded
into one measurement reports the target as slower than it is and hides that it
answered wrongly first — and the p99 of a retried step becomes a number
describing an entirely different thing. Two counters, attempts and requests,
keep both facts.

A check is a function because anything else invites a query language, and a
query language over a response body is a project of its own. The name is
required rather than derived: a report that says `check failed` for three
different checks is a report nobody can act on.

## Stack

- [ ] **`spec-0026-checks`** — `checking`, its failure reason, and the
      response body being available to it.
      Done when: a 200 that fails a check fails the step under the check's
      name, and the body is read once.
- [ ] **`spec-0026-attempts`** — attempts recorded separately from requests,
      on `StepStats` and on the page.
      Done when: a step retried once reports two attempts and one request.
- [ ] **`spec-0026-retrying`** — `retrying(times, on)`, with a backoff that
      cannot be a `Thread.sleep` in library code.
      Done when: a step retries only on the condition given, and the delay
      between attempts is on the scheduler.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

Answered by the architect:

1. **A check reads the body, so a checked request holds it in memory.** Stated
    on the method rather than discovered: a load test that streams a large
    response and also checks it is asking for both, and cannot have both.
2. **Retries are capped and the cap is reported.** An uncapped retry against a
    failing target is a denial-of-service written by accident.
3. **A retried step's service time is the last attempt's**, with the total
    across attempts reported separately. Anything else buries the retry.
