# 0058 — What was actually sent

## Problem

A scenario that is wrong reports `status 404` and stops there. Nothing says
what URL the path template became after substitution, what headers went with
it, what the body was, or what came back. A failed capture says
`no orderId captured` and not what it was looking in.

So the authoring loop is: add a `println` inside a step body, run it, read it,
take it out. A `println` in a step body is timed as that step, so the run that
diagnoses the problem is a run whose numbers are wrong — and people forget to
take them out.

This is the first hour with the tool, every time, for everyone. 0023 puts a
trace id beside a percentile so a slow request leads somewhere; this is the
different question of what a single request *was*.

## Not doing

- No logging framework, and no log level. Core has no logging dependency.
- No per-request logging under load. A million lines is not a debugging aid,
  and formatting on the measured path is the bug this exists to avoid.
- No proxy, no recorder, no HAR import. Capturing a browser session is its own
  project.
- No response body in the report. What a step returned at p99 is 0023's
  exemplar, pointing outward.

## Shape

One user, once, printed — a run whose purpose is reading rather than measuring:

```kotlin
proofload.trace(checkout)          // one user, one pass, no profile, no result
```

```
browse        GET  https://orders.internal/products              200   12ms
place order   POST https://orders.internal/orders                201   31ms
              > content-type: application/json
              > {"cart":"1 anvil"}
              < location: /orders/9f3
              captured orderId = "/orders/9f3"
pay           POST https://orders.internal/orders/9f3/pay        503   —
              < {"error":"anvil out of stock"}
              FAILED status 503 — user abandoned here
```

- `trace(scenario)` takes a scenario rather than a simulation: there is no rate
  and no duration, because one user is the point.
- It returns nothing. A traced pass is not a measurement and must not be
  mistaken for one.
- A feeder can be supplied, so the user gets the data a real one would.

## Why this shape

A separate entry point rather than a flag on a run. A flag means the printing
code lives on the path that a hundred thousand requests a second take, guarded
by a branch that is right until somebody flips it in CI. A separate call cannot
be flipped on by accident, and the code that formats an exchange never gets
loaded by a run that measures.

Returning nothing is the same argument as the one that makes a closed run say
what it cannot measure. A trace runs one user with no schedule, so every number
it could report — a latency, a count — describes a cold JVM sending one
request. Handing that back as a `RunResult` would put it in front of
`writeHtmlReport`, and a page from it would be indistinguishable from a real
one.

The alternative worth naming: keeping the first few failed exchanges from a
real run and putting them on the page. That is genuinely useful and it is a
different spec — it needs a bound on what is retained, a redaction story for
request bodies, and an answer for what a shared report may carry.

## Stack

- [x] **`spec-0058-trace`** — `trace(scenario)` in the engine, running one user
      through the ordinary step machinery.
      Done when: a three-step scenario prints three lines in order, and a
      failing step prints the failure and stops.
- [x] **`spec-0058-exchange`** — the HTTP module reporting the substituted URL,
      the headers it sent, the body and the response.
      Done when: a templated path prints filled, a capture prints the value it
      took, and a failed capture prints what it was reading.
      Unblocked by `spec-0075-seam`, and without the flag this spec rejects:
      whoever runs the step builds the scope, so a trace builds one that
      collects notes and a run builds one that does not. There is no setting
      to flip — a measuring run cannot be turned into a narrating one — and a
      body asks `narrating` before it builds a line, so a run builds none.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **How does the exchange reach the printer without the HTTP module knowing
    about printing?** Recommend a core-declared sink that the step machinery
    passes down and that is null on a measuring run, so the branch is on a
    reference the JIT sees as constant. The alternative — the HTTP module
    printing directly — puts formatting in a leaf module and makes every future
    protocol module reimplement it.
2. **Are bodies truncated?** Recommend yes, at a couple of kilobytes, with the
    truncation marked. A trace that dumps a megabyte of HTML is one nobody
    reads.
3. **Are headers redacted?** Recommend an `authorization` header printed as its
    scheme and a length. This prints to a developer's own terminal, but it is
    the terminal people paste into issues.
4. **Should `trace` be a Gradle task rather than a call?** A task would need a
    way to name a scenario from the command line, which means a registry, which
    `AGENTS.md` rules out. Recommend the call, from a `main` or a test.
