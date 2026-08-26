# 0023 — A number you can follow

## Problem

The report says `/pay` took 302 ms at p99 and cannot say what those 302 ms were
spent on. Kestrel measures from outside the service and has no view inside it,
so "which part of that was the database" is a question it can only answer by
handing the reader somewhere else to look.

Teams already have that somewhere else — Jaeger, Tempo, Datadog, Honeycomb —
and the only missing piece is the join: a trace id, recorded next to the
measurement, for a request that was actually slow.

## Not doing

- No span export, no OTel SDK, no collector. That is a leaf module if anyone
  wants it, and it is not this spec.
- No tracing of every request. At fifty thousand a second, full tracing changes
  what is being measured — the target spends real time exporting spans — and
  then the load test is a test of the tracing.
- No trace analysis. Kestrel does not read traces; it points at them.
- No sampling policy beyond the exemplars below.

## Shape

```kotlin
val api = http.baseUrl("https://orders.internal").traced()
```

and in the report:

> `/pay` p99 **302 ms** — trace `4bf92f3577b34da6a3ce929d0e0e4736`

- W3C `traceparent` on every request, generated in `kestrel-http` — a formatted
  hex string, so no dependency.
- **Exemplars**: one trace id kept per histogram bucket, not per request. That
  is a string per bucket, tens per step, and it answers "show me a request that
  landed at p99" exactly.
- `baggage` carrying a flag that says this is load, so the traffic can be told
  apart from real users downstream.

## Why this shape

An exemplar is the smallest thing that answers the question. Keeping a trace id
per request is a memory profile; keeping one per bucket costs nothing and lands
a reader on a real request at the latency they are asking about.

Kestrel's number and the trace's number will disagree, and the gap is
informative rather than a bug: Kestrel times from the intended departure, and a
server span starts when the request was accepted. The difference is network
plus the target's accept queue.

Marking the traffic as synthetic is what makes it safe to point at a shared
environment. A load test that silently pollutes production dashboards and
triggers autoscaling gets banned by whoever owns the target.

## Stack

- [ ] **`spec-0023-traceparent`** — generating and sending `traceparent` and
      the load-marking `baggage` entry.
      Done when: every request carries a well-formed header, ids are unique per
      request, and an untraced client sends neither.
- [ ] **`spec-0023-exemplars`** — one trace id per bucket, carried on `Timing`.
      Done when: a histogram of a thousand samples keeps tens of ids, and the
      id reported beside p99 belongs to a request in that bucket.
- [ ] **`spec-0023-report`** — the exemplar beside the percentile on the page.
      Done when: the page shows an id for p99 where one was recorded, and says
      nothing where tracing was off.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

Answered by the architect:

1. **Ids are generated from a per-thread seeded generator**, not
    `SecureRandom`. These identify a request in a load test; they are not
    secrets, and a blocking entropy source on the hot path is a stall.
2. **Exemplars are kept for the last sample in each bucket**, not the first: a
    late run is more interesting than a cold one.
3. **No sampling flag is set on `traceparent`.** Whether to record is the
    target's decision, and a load generator forcing it would be deciding the
    sampling policy of a system it does not own.
