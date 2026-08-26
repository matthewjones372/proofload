# 0040 — An answer that arrives somewhere else

## Problem

`Action.run(Session): StepResult` closes a sample where it opened it. That is
right for HTTP, for JDBC, and for any in-process stage somebody wants to time,
which is most of what people load test.

It cannot describe a pipeline. Publish a record to a topic and the answer
appears at a sink, in another process, seconds later. Today the only thing
Kestrel could time is the publish, and that is the trap rather than a
limitation: measured from ingestion, a pipeline's latency stays flat while the
system falls apart, and measured from the event's creation it escalates
`[EVENTTIME]`. The metric that looks best is the one that lies, and it is the
one every dashboard shows.

There is a second thing only this shape can measure. A record that never arrives
is not a missing sample, it is the finding. A throughput number from a pipeline
that silently drops records is worthless, which is why streaming benchmarks ship
a validator `[ORACLE]` and load tools do not.

## Not doing

- No Kafka module here. This spec is the shape in core; the first
  implementation is a separate one and will argue its own dependencies.
- No exactly-once semantics, no ordering assertions. Arrival and its latency.
- No change to `Action`. Synchronous steps stay exactly as they are.
- No windowing or aggregation. What arrives, when, and what did not.

## Shape

```kotlin
val submitted = step("submitted")
val settled = step("settled")

val trades = scenario("trades") {
    emit(submitted, kafka.topic("trades").keyed { it[account] })
}

val simulation = trades.at(5_000.perSecond, over = 5.minutes)
    .completing(settled, from = kafka.topic("settlements"))

result[settled].serviceTime.p99      // sink observation minus intended departure
result[settled].unmatched            // 41 records never arrived
result[settled].inFlight             // 12 still moving when the run ended
```

- `Step.Emit` — a step that departs with a correlation id and its intended
  departure, and does not wait.
- `Completions` — an interface core declares and a leaf module implements,
  exactly as `Action` is declared in core and implemented by `kestrel-http`.
- Latency is the sink's observation minus the **intended** departure, which is
  the same clock `responseTime` already uses and the reason it will be honest
  under backpressure.
- `unmatched` and `inFlight` are separate: one is a record that is gone, the
  other is a record the run did not wait for.

## Why this shape

Three ways in. A parallel `Emit` step with a `Completions` source declared in
core keeps the existing model untouched and puts the new concept beside the old
one at the same level. Making `Action` return a pending result would unify them
and make every synchronous step carry an abstraction it never uses. A separate
result type in a separate module would leave core alone at the cost of two
report shapes, two goal vocabularies and a fork in the DSL that users have to
learn. Recommend the first.

The correlation id is the part that will leak into user code, and it should be
small: a long, carried in a header or a field the user names, and matched by the
sink. Anything richer becomes a serialisation format this tool has no business
owning.

## Stack

- [ ] **`spec-0040-emit`** — `Step.Emit`, the intended departure carried with
      it, and a result that can hold unmatched and in-flight counts.
      Done when: an emit step with an in-memory completions source records
      latency from the intended departure, and a dropped record is counted as
      unmatched rather than ignored.
- [ ] **`spec-0040-completing`** — `completing(step, from = ...)` on a
      simulation, and the run waiting a bounded time for stragglers at the end.
      Done when: a run whose sink lags reports the stragglers that arrived
      inside the wait and the rest as in flight.
- [ ] **`spec-0040-page`** — unmatched and in-flight on the report, prominently.
      Done when: the golden shows a run that lost records and does not bury it.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **How long does a run wait at the end?** Recommend a required drain window on
    `completing`: a default would silently turn lost records into in-flight ones
    or the reverse.
2. **Where does the correlation id live?** Recommend the user says, with a
    function from the emitted value to a long, so the tool never owns a wire
    format.
3. **Does an emit step have a service time at all?** Recommend recording the
    publish separately under the same step, since a slow producer and a slow
    pipeline are different problems and the numbers should not be added
    together.
4. **What does `fellBehind()` mean here?** The same as everywhere: the injector
    could not depart on schedule. Worth stating, because a reader will assume it
    means the pipeline is behind, and the pipeline being behind is the latency.
