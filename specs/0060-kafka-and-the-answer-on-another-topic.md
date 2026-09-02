# 0060 — Kafka, and the answer that arrives on another topic

## Problem

0040 built `emit`, `completing`, `Correlation` and `Completions` for a system
that answers somewhere else, and its worked example is Kafka —
`kafka.topic("trades")`, `completing(settled, from = kafka.topic("settlements"))`.
No module carries a broker. `InMemoryCompletions` is the only implementation and
its own KDoc calls it "what stands in for a broker until a module carries one".
So the one shape this tool has for asynchronous work cannot be pointed at the
system people most often mean by it.

Two questions get conflated here and they want different answers. **Is the
adapter right** — does a correlation survive, does a failed send name its reason
— is this repository's, and it needs no broker. **Is my cluster sized right** —
do these partitions and this replication hold at fifty thousand a second — is
the user's, and it needs *their* cluster, not one Kestrel ships. A module that
is correct, and whose own overhead is known, serves the second without ever
running a broker in its own build. What that overhead is, and what proves it,
is 0061's.

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
  load against one.
- No advice about cluster sizing. This tool measures; a partition count is the
  reader's conclusion from what it measured.
- No broker, embedded or containerised. 0061 owns what is proved over a real
  socket and what the path costs.
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

Keeping the serializer out is what keeps the module publishable, and it keeps
the measurement honest in a way an owned serializer would not: whatever
serialization costs, it is the caller's code running on the departure thread,
visible as such rather than hidden inside a step this tool wrote.

One producer, shared. A `KafkaProducer` is thread-safe and built to be shared,
which is the opposite of 0055's per-user cookie jar and for the opposite reason:
a jar is per-user state, a producer is a connection pool. A producer per virtual
user would build a sender thread and a buffer inside the sample.

The alternative shape is a `kafka.request()` step that produces and waits for
the ack, so a run reads like the HTTP one. It is simpler, it needs none of 0040,
and it measures the wrong thing: acks are a broker's write, and a team that
tunes against them will ship a consumer that cannot keep up.

## Stack

- [x] **`spec-0060-produce`** — `kestrel-kafka`, `kafka.brokers`, `topic`,
      `keyed`, `value`, and `emit` producing through it; a send that fails
      recorded with the broker's reason.
      Done when: a scenario produces through a `MockProducer` and every record
      carries the key, value and correlation the scenario named; a send that
      fails is a failed step naming why; the module's runtime classpath is
      `kafka-clients`, core and the JDK.
- [x] **`spec-0060-completing`** — `Completions` over a consumer, and
      `correlatedBy` reading the id from a header.
      Done when: a run whose completions come from a second topic reports
      `unmatched` and `inFlight` as 0040 defines them.
- [x] **`spec-0060-round-trip`** — the produced records fed to the consuming
      side through `MockProducer` and `MockConsumer`, and a stub registry on
      `com.sun.net.httpserver`.
      Done when: a correlation header written by an `emit` is the one
      `Completions` matches on; a caller's serializer resolves a schema against
      the stub; nothing new is on the test classpath; and it runs inside
      `./gradlew build`.
- [x] **`spec-0060-recipe`** — the schema registry page in `docs/cookbook.md`.
      Done when: the page says a serializer fetches its schema once per subject
      and caches it, so the first record pays an HTTP round trip; that the stall
      lands in `behind` rather than the target's latency, because the lambda is
      the caller's code on the departure thread; and that `result.steady` is
      where to read the run without it.
      **The middle claim is wrong, and measured wrong.** The lambda runs inside
      the step body, so the fetch is in that step's own *service time*; `behind`
      is scheduling lateness and stays clean, because every user runs on a
      thread of its own and one blocked in a serializer holds up nobody's
      departure. `RegistryTest` asserts both halves. The page says the true
      thing instead: the first record — and any that depart while it is
      fetching, since a cached serializer makes them wait on it — carries the
      round trip as its own latency, and reading that as the broker's is the
      mistake. `result.steady` is still where to read the run without it.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
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
3. **Where does the correlation id live?** A header keeps it out of the payload
    and needs no deserializer on the completion side, which is the only option
    that does not drag the registry back in. Recommend a header, with the key as
    a second option, and reading from the payload refused rather than supported.
    Built as a header. The `Correlation` is stated once, at the `emit` call
    site, and threaded to both the departure the run counts and the header the
    record carries: naming it twice is how a run ends up matching on an id it
    never sent.
6. **A record on the completions topic carrying no id this run can read.**
    Counted on the sink rather than dropped in silence: it answers no
    departure, so without the counter every one of them shows up as something
    unmatched with nothing on the page to say the id was the problem rather
    than the consumer.
5. **What a produce step does when the scenario gave it no value.** Refused as
    `NothingToSend` rather than produced empty: a record nobody meant to send
    is a row in the report and a message on somebody's topic.
4. **A blocking `send`.** When the accumulator fills, `send` blocks up to
    `max.block.ms` on the calling thread. That is real backpressure and 0040's
    intended-departure clock reports it honestly, but it will show up as
    generator lateness rather than target latency. Recommend saying so where
    `fellBehind()` is explained rather than special-casing it.
