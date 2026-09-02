# 0061 — What the Kafka path costs, and what a socket proves

## Problem

0011 asked what this tool costs and answered it: `benchmarks/Ceiling.kt` finds
the rate at which the generator stops keeping its own schedule, against a step
that touches no socket, so "the only thing between the departure a profile
promised and the sample a recorder took is this tool". 0060 adds a path that
number does not cover — a serializer the caller wrote, a producer's accumulator,
a sender thread, an ack callback — and none of it has been weighed.

That matters because of who is asking. A team pointing Kestrel at their own
cluster is asking whether *the cluster* holds at a rate. They can only believe
the answer if the generator's own cost at that rate is known and small. Today it
is neither known nor stated, and a Kafka run would quietly attribute the tool's
overhead to the broker.

The second half is what a real broker proves that a mock does not, and it is
less than it first appears. That `kafka-clients` speaks Kafka is Apache's test
suite, not this repository's. What is genuinely unproven without a socket is the
producer's *accumulator* — batching, `max.block.ms`, the sender thread — which
`MockProducer` does not model at all.

## Not doing

- No embedded broker. Weighed rather than argued: `kafka-clients` is 19.9 MB
  across 5 jars and 0060 needs it anyway; `kafka_2.13` is 42.9 MB across 45;
  `embedded-kafka_2.13` is 43.0 MB across 46, so the wrapper is free and the
  weight is the broker; Testcontainers Kafka is 23.0 MB across 13 but wants
  Docker. Twenty-three megabytes and forty jars on every `./gradlew build` is
  not a price this pays.
- **No simulated broker latency.** A delay this tool injects is a constant it
  chose, not a measurement, and printing one beside real numbers is the lie
  `AGENTS.md` exists to stop. A null target is legitimate — 0011 and 0056 both
  use one — because it claims nothing about a broker.
- No change to what 0060's module does or measures.

## Shape

A Kafka ceiling beside the existing one, in `benchmarks`:

```bash
./gradlew :benchmarks:kafkaCeiling
```

```
producing through a producer that reaches no broker
  rate      p99 late    verdict
  10,000/s    412us     kept its schedule
  50,000/s   2.31ms     kept its schedule
 100,000/s   38.4ms     fell behind
```

and, opted into rather than required:

```bash
./gradlew :kestrel-kafka:containerTests   # the real images, where Docker exists
```

## Why this shape

`Ceiling.kt` is the precedent and the shape: the difference between a departure
the profile promised and the sample a recorder took, with the target removed.
`MockProducer` is that removal for Kafka — it is in `kafka-clients` already, so
the benchmark costs no dependency, and what it leaves in the path is exactly
what 0060 added: the caller's serializer, the record construction, the header,
the callback bookkeeping.

It also leaves something *out*, and the number is worthless unless the page says
so: `MockProducer` completes without an accumulator, a batch or a sender thread,
so this ceiling is the adapter's cost and not the real producer's. That is the
strongest argument for a fake broker — not correctness, which the mock round
trip in 0060 already covers, but cost measurement with the accumulator in.

Costed rather than promised: every protocol class is in `kafka-clients`,
`RequestHeader.parse` and `AbstractRequest.parseRequest` are public, and a
Produce request carries a valid record batch a Fetch response can hand straight
back, so no batch encoding is needed. Against it,
`AbstractResponse.serializeWithHeader` is package-private, so responses need a
hand-written header, and flexible versions put tagged fields in that header —
subtle, silent when wrong, and a spike before it is a promise.

Containers stay for whoever has Docker, as a smoke test that the module works
against a genuine broker at least once somewhere — not as cluster validation,
which is the user's own cluster's job.

## Stack

- [x] **`spec-0061-ceiling`** — `kafkaCeiling` in `benchmarks`, and its figure in
      `docs/what-it-costs.md`.
      Done when: the table names a rate at which the Kafka path stops keeping
      its schedule; the page states that the accumulator is not in it; and
      `benchmarks` is still outside the Kover aggregation and outside `check`.
- [x] **`spec-0061-spike`** — a throwaway proving whether a fake broker built
      from `kafka-clients`' own protocol classes can satisfy a real
      `KafkaProducer` and a real `KafkaConsumer` using `assign()`.
      Done when: it either produces and fetches over a socket with no new
      dependency, or it is written up as refused with the reason.
- [ ] **`spec-0061-containers`** — the round trip against the real broker and
      registry images, opted into rather than required.
      Done when: it runs where Docker is present, is skipped rather than failed
      where it is not, and nothing in `build` depends on it.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :benchmarks:kafkaCeiling
```

## Open questions

1. **Does the ceiling belong to this repository or to the reader?** A number
    measured here describes this machine. 0056 is already building a loopback
    target so a reader can take their own. Recommend publishing the figure with
    the machine beside it, as `docs/what-it-costs.md` already does, and pointing
    at 0056 for taking a local one.
2. **Is a ceiling without the accumulator worth having?** It bounds the
    adapter, which is the part this repository wrote, and it is free. Recommend
    yes, on the condition that the page says what is missing — an unlabelled
    number here would be the exact failure this spec's "not doing" refuses.
    Built with two labels rather than one, because building it turned up a
    second thing that had to be said. The median rule this repository uses to
    name a ceiling calls 100,000 a second "kept its schedule" while the 99th
    percentile departure is 179 ms late; the median is 87 µs. So the page says
    the ceiling the rule names, says the tail beside it, and says plainly that
    the named rate is not one anyone should drive. The rule is kept rather than
    changed only because it is the one thing making the three sweeps
    comparable.
5. **`MockProducer` is not what the sweep uses.** The spec proposed it, and it
    retains every record handed to it: at a hundred thousand a second over five
    seconds that is half a million records held live, and the allocation and
    collection of that list would have been measured as the adapter's cost. The
    sweep uses a producer written in `benchmarks` that answers immediately and
    keeps nothing. The dependency argument is unchanged — it needs nothing that
    was not already there.
6. **`fellBehind()` is not in the table.** It says yes at every rate, for the
    reason it does on the null step: a producer that answers immediately has no
    response time for the backlog to be large against. A constant column tells a
    reader nothing and reads like it does, so the page says why instead.
3. **What does `docs/modules.md` say for a module whose wire is untested
    without Docker?** Every other row there names a test that proves its claim.
    Recommend saying plainly that the wire is proved only by the container
    suite, rather than letting the row read like its neighbours.
4. **Does the spike get a time box?** A fake broker is the kind of thing that is
    eighty percent done for a long time. Recommend one sitting, and a written
    refusal being an acceptable and useful outcome. **It worked, in one
    sitting, and further than expected.** A real `KafkaProducer` — idempotence
    on, its accumulator, its sender thread, its ack path — and a real
    `KafkaConsumer` using `assign()` both work over a socket against a broker
    built entirely from `kafka-clients`' own protocol classes, with no
    dependency this module did not already have. Six requests answer it:
    `ApiVersions`, `Metadata`, `InitProducerId`, `Produce`, `ListOffsets`,
    `Fetch`.
    The two things the spec worried about were both real and both small.
    `AbstractResponse.serializeWithHeader` is package-private, so the response
    header is written by hand — `ApiKeys.responseHeaderVersion(apiVersion)` is
    public and is what decides whether tagged fields follow it. And the batch
    problem does not exist: a Produce request carries a valid record batch and
    a Fetch response takes one, so the batches go back out exactly as they came
    in and nothing here encodes one.
    The one thing not anticipated: idempotence has been on by default since
    3.0, so the client's transaction manager refuses to send at all until
    `InitProducerId` is answered. Turning idempotence off in the test would
    have hidden that, and would have measured a producer nobody runs.
5. **Does the spike change what `spec-0061-containers` is for?** It removes the
    strongest reason for it. The wire is now proved without Docker, by a
    producer and consumer that are the real ones. What containers would still
    add is a genuine broker's own behaviour — retries under real leader
    changes, a registry answering for real — which is further from what this
    repository can claim and closer to the user's own cluster's job.
