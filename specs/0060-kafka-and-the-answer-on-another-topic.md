# 0060 — Kafka, and the answer that arrives on another topic

## Problem

0040 built `emit`, `completing`, `Correlation` and `Completions` for a system
that answers somewhere else, and its worked example is Kafka —
`kafka.topic("trades")`, `completing(settled, from = kafka.topic("settlements"))`.
No module carries a broker. `InMemoryCompletions` is the only implementation and
its own KDoc calls it "what stands in for a broker until a module carries one".
So the one shape this tool has for asynchronous work cannot be pointed at the
system people most often mean by it.

The measurement is also not the one an HTTP module makes. A broker acking a
produce is not a consumer having done the work, and a team load-testing Kafka
almost always wants the second. `emit`/`completing` already answers that — the
latency is the sink's observation minus the *intended* departure — so the gap is
an adapter, not a design.

## Not doing

- **No schema registry dependency.** `io.confluent:kafka-avro-serializer` and
  `kafka-schema-registry-client` are not on Maven Central (checked: 404 against
  repo1, where `org.apache.kafka:kafka-clients` is 200). Depending on them would
  force a `packages.confluent.io` declaration on every consumer of a published
  module, and would break 0029's smoke project, which resolves from
  `mavenCentral()` on purpose.
- **No Kestrel-owned serializer.** Avro, Protobuf and JSON Schema are the
  caller's choice and none of them is this tool's business.
- No consumer-group lag monitoring. That is observing a system, not generating
  load against one, and it belongs in a spec of its own.
- No broker inside `./gradlew build`. A real one runs in a task of its own, on
  0041's pattern; see below.
- No second engine. The producer runs on virtual threads like everything else.

## Shape

A leaf carrying `org.apache.kafka:kafka-clients` and nothing else:

```kotlin
val broker = kafka.brokers("localhost:9092").acks(ALL)

val trades = scenario("trades") {
    emit(
        submitted,
        broker.topic("trades")
            .keyed { it[account] }
            .value { avro.serialize(it[trade]) },   // the caller's serializer
    )
}

trades.at(5_000.perSecond, over = 5.minutes)
    .completing(settled, from = broker.topic("settlements").correlatedBy(Header("trade-id")))
```

The value is a `(Session) -> ByteArray` the caller supplies, so a Confluent
`KafkaAvroSerializer`, a plain `ByteArraySerializer` or a hand-rolled one all
work and the module never learns which.

## Why this shape

Keeping the serializer out is what keeps the module publishable. It also keeps
the measurement honest in a way an owned serializer would not: whatever
serialization costs, it is the caller's code running on the departure thread,
and it is visible as such rather than hidden inside a step this tool wrote.

Two tiers of test, because this is the first module that cannot be proved
against a socket it starts itself. 0041 has already had this argument in another
key: a class of test that is real but cannot share `build` gets a tag, a task, an
exclusion from Kover instrumentation, and a gate that fails the build if the two
ever meet. `broker` is that shape again, for an environment rather than a clock.
What `build` then proves is the mapping, which is all the adapter is; what the
tagged task proves is the wire, the serializer and the registry.

One producer, shared. A `KafkaProducer` is thread-safe and built to be shared,
which is the opposite of 0055's per-user cookie jar and for the opposite reason:
a jar is per-user state, a producer is a connection pool. A producer per virtual
user would build a sender thread and a buffer inside the sample.

The alternative shape is a `kafka.request()` step that produces and waits for
the ack, so a run reads like the HTTP one. It is simpler, it needs none of 0040,
and it measures the wrong thing: acks are a broker's write, and a team that
tunes against them will ship a consumer that cannot keep up.

## Stack

- [ ] **`spec-0060-produce`** — `kestrel-kafka`, `kafka.brokers`, `topic`,
      `keyed`, `value`, and `emit` producing through it; a send that fails
      recorded with the broker's reason.
      Done when: a scenario produces through a `MockProducer` and every record
      carries the key, value and correlation the scenario named; a send that
      fails is a failed step naming why; the module's runtime classpath is
      `kafka-clients`, core and the JDK.
- [ ] **`spec-0060-completing`** — `Completions` over a consumer, and
      `correlatedBy` reading the id from a header.
      Done when: a run whose completions come from a second topic reports
      `unmatched` and `inFlight` as 0040 defines them.
- [ ] **`spec-0060-broker`** — a `broker`-tagged task running the module
      against a real Kafka in a container, and the root gate widened from one
      tag to a set.
      Done when: `./gradlew build --dry-run` lists neither `timingTests` nor
      `brokerTests`, reverting either exclusion fails the build by name, and the
      tagged task produces and consumes against a real broker.
- [ ] **`spec-0060-recipe`** — the schema registry page in `docs/cookbook.md`:
      the `packages.confluent.io` declaration, the serializer wired into
      `value { }`, and what the first record costs.
      Done when: the page says that a serializer fetches its schema once per
      subject and caches it, so the first record pays an HTTP round trip; that
      the stall lands in `behind` rather than in the target's latency, because
      the lambda is the caller's code on the departure thread; and that
      `result.steady` is where to read the run without it.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew build --dry-run | grep -cE "timingTests|brokerTests"   # 0
./gradlew :kestrel-kafka:brokerTests                             # needs a container
```

## Open questions

1. **`linger.ms` makes the arrivals figure a lie.** 0034 reports the spacing the
    run produced and its coefficient of variation. A producer that lingers turns
    an evenly spaced departure stream into bursts at the broker, so the page
    would report a smoothness the broker never saw. Recommend defaulting
    `linger.ms` to 0, printing it on the page whatever it is, and saying in the
    docs that a non-zero linger makes the arrivals figure describe the injector
    rather than the broker.
2. **What does the produce step time?** With `acks=all` it is the ISR round
    trip; with `acks=0` it is nothing at all. Recommend recording the ack as the
    step's service time, printing the `acks` setting beside it, and leaning on
    `completing` for the number that matters.
3. **What does `build` prove about a module it cannot run a broker for?**
    That the adapter maps — key, value, header, correlation, the reason a failed
    send gives — which `MockProducer`/`MockConsumer` answer in-JVM, and which is
    the whole of the adapter's job. 0040's shape is covered already by
    `InMemoryCompletions`, and whether `kafka-clients` works is Apache's
    question, not this repository's. The wire, the serializer and the registry
    are the `broker` task's. Recommend saying exactly that in
    `docs/modules.md`, where every other module's claim is a test, rather than
    letting this one row quietly mean less than the others.
4. **Where does the correlation id live?** A header keeps it out of the payload
    and needs no deserializer on the completion side, which is the only option
    that does not drag the registry back in. Recommend a header, with the key as
    a second option, and reading from the payload refused rather than supported.
5. **A blocking `send`.** When the accumulator fills, `send` blocks up to
    `max.block.ms` on the calling thread. That is real backpressure and 0040's
    intended-departure clock reports it honestly, but it will show up as
    generator lateness rather than target latency. Recommend saying so where
    `fellBehind()` is explained rather than special-casing it.
