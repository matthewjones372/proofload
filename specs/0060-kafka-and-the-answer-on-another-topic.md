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
- **No Docker required, and no embedded broker.** A container suite exists for
  whoever wants the real images, and nothing gates on it. See the weights below.
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

Nothing heavy on the test classpath, and no Docker in the way. Resolved and
weighed rather than guessed:

| on the test classpath | size | jars |
|---|---|---|
| `kafka-clients`, which the module needs anyway | 19.9 MB | 5 |
| `kafka_2.13`, a real broker | 42.9 MB | 45 |
| `embedded-kafka_2.13` | 43.0 MB | 46 |
| Testcontainers Kafka | 23.0 MB | 13 |

An embedded broker is 23 MB and forty jars on top of what is already there,
paid by every `./gradlew build` forever, and the wrapper is free — the weight
is the broker. It buys a real socket, and what actually needs proving over one
is narrower than it looks: that `kafka-clients` speaks Kafka is Apache's test
suite, not this repository's, and the registry is answered by a stub on the
`com.sun.net.httpserver` everything else here tests against, precisely because
this module does not own the serializer.

What is left, and genuinely Kestrel's, is that a correlation header survives
from the producing side to the `Completions` side. `MockProducer` and
`MockConsumer` ship inside `kafka-clients` and can be wired to each other, so
that round trip costs nothing and stays inside `build`. A real broker is then
an opt-in tier rather than the price of admission.

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
- [ ] **`spec-0060-round-trip`** — the produced records fed to the consuming
      side through `MockProducer` and `MockConsumer`, and a stub registry on
      `com.sun.net.httpserver`.
      Done when: a correlation header written by an `emit` is the one
      `Completions` matches on; a caller's serializer resolves a schema against
      the stub; nothing new is on the test classpath; and this runs inside
      `./gradlew build`.
- [ ] **`spec-0060-containers`** — the same suite against the real broker and
      registry images, opted into rather than required.
      Done when: it runs where Docker is present, is skipped rather than failed
      where it is not, and nothing in `build` depends on it.
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
./gradlew :kestrel-kafka:containerTests   # the real images, where Docker exists
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
3. **Is a socket worth 23 MB?** The alternative nobody has costed is a fake
    broker built from `kafka-clients`' own protocol classes, which are all
    present: `RequestHeader.parse` and `AbstractRequest.parseRequest` are public,
    and a Produce request carries a valid record batch that a Fetch response can
    hand straight back, so no batch encoding is needed. It would add nothing to
    the classpath. Against it: `AbstractResponse.serializeWithHeader` is
    package-private, so responses need a hand-written header, and flexible
    versions put tagged fields in that header — a thing to get subtly and
    silently wrong. Recommend a spike before it is promised, and the mock round
    trip in the meantime, which needs no spike and no jars.
4. **Where does the correlation id live?** A header keeps it out of the payload
    and needs no deserializer on the completion side, which is the only option
    that does not drag the registry back in. Recommend a header, with the key as
    a second option, and reading from the payload refused rather than supported.
5. **A blocking `send`.** When the accumulator fills, `send` blocks up to
    `max.block.ms` on the calling thread. That is real backpressure and 0040's
    intended-departure clock reports it honestly, but it will show up as
    generator lateness rather than target latency. Recommend saying so where
    `fellBehind()` is explained rather than special-casing it.
6. **Does anything real run on this repository's own machine?** With the mock
    round trip in `build` and containers opted into, the answer on a machine
    without Docker is no — the wire is exercised nowhere. That is a smaller gap
    than it sounds, since the wire belongs to `kafka-clients`, but it is a gap
    and `docs/modules.md` should say so in the row for this module rather than
    let it read like every other one.
