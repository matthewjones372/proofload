# 0117 — Kafka 4, and the fake that answers it

## Problem

`kafka-clients` is held at 3.9.1 because 4.3.1 breaks this module. Held, not
broken: PR #68 ships the other eighteen updates and names this one as deferred.

The compile breakage is trivial and was measured, not guessed. One import moved —
`MemoryRecords` is now `org.apache.kafka.common.record.internal.MemoryRecords`,
public through 4.0.0 — and one constructor went away,
`MockProducer(boolean, Serializer, Serializer)`, at four identical call sites. With
an import change and a `null` partitioner threaded through those four, the module
compiles clean and eighteen of its twenty tests pass.

The two that fail are the point. `FakeBrokerTest`'s "a real producer connects,
batches and produces over a socket" and "a real consumer assigns a partition and
fetches what was produced" both **time out after 60 seconds**: a 4.x client never
finishes a handshake with the fake broker written here. That is not a rename to
chase. It is a hand-written implementation of someone else's wire protocol, and the
protocol moved.

## Not doing

- **Not unholding the version on its own.** A green compile with two hanging tests
  is worse than a pin, because the pin says why.
- **Not chasing the timeout blind.** Whether the fake is worth porting is the
  question below; porting first answers it by accident.
- **Not touching the produce path in `main`.** Nothing in `proofload-kafka`'s own
  source failed. This is fixtures and tests.

## Shape

Two ways, and they are alternatives rather than an order.

**A — port the fake.** Find what a 4.x client asks for that the fake does not
answer, most likely an `ApiVersions` range where 4.0 dropped the older end, and
teach it. Keeps the tests hermetic and fast, keeps a dependency on
`record.internal`, and buys the same bill again at Kafka 5.

**B — delete the fake, and let 0061 answer instead.** 0061's remaining stack entry
is exactly this: "the round trip against the real broker and registry images, opted
into rather than required. Done when: it runs where Docker is present, is skipped
rather than failed" otherwise. A real broker is correct by construction, needs no
internal package, and cannot drift from a protocol because it *is* the protocol.

```kotlin
// B, in outline: the fixture becomes a container rather than a socket
@EnabledIfDockerAvailable
class BrokerRoundTripTest { /* the two tests FakeBroker exists to make possible */ }
```

## Why this shape

**The recommendation below was made before reading 0061 in full, and 0061
disagrees with it.** That entry is marked "deliberately not built, and the owner's
call to reverse", and its reasoning is that the spike *removed* the case for
containers: the wire is proved without Docker, by a real producer and a real
consumer over a real socket, inside `./gradlew build`, with no dependency at all.
0061 also costed containers — Testcontainers Kafka at 23.0 MB across 13 jars — and
refused the weight. `FakeBroker` is not debt someone tolerated; it is a spike that
worked, and it catches things a mock cannot, idempotence being on by default since
3.0 among them.

Against that, B deletes dependency-free coverage that runs everywhere and loses the
wire test wherever Docker is absent, to avoid one internal import on one line. A
now looks like the better trade, and the decision in entry 2 should be taken with
0061 open. What follows is the argument as first written.

**B was recommended.** `FakeBroker` exists so a round trip can be tested without a
broker, and it is now the only reason this repository reads a package Kafka has
marked internal. The tests it supports are precisely the two that a container would
run for real, and 0061 already argues for them — this makes that entry load-bearing
rather than nice to have.

A is cheaper this week and more expensive every year: a fake broker is a promise to
track someone else's protocol forever, and the 60-second timeouts are what that
promise costs when it is not kept. The counter-argument is real, though — container
tests are slower, need Docker, and are skipped exactly where CI is thinnest, so
some hermetic coverage would be lost rather than moved.

Whichever way, the four `MockProducer` call sites and the `MemoryRecords` import
are mechanical and land first, since they are needed either way.

## Stack

- [ ] **`spec-0117-compiles`** — the import, the four constructors, and
      `kafka-clients` still at 3.9.1 so nothing changes behaviour yet.
      Done when: the module compiles against both 3.9.1 and 4.3.1, and
      `FakeBrokerTest` is the only thing that fails on 4.3.1.
- [x] **`spec-0117-decide`** — **A.** The fake is ported and kept.
      0061's containers entry stays unbuilt for the reasons it already gives: the
      spike proved the wire with a real producer and consumer over a real socket,
      inside `./gradlew build`, on every runner, for no dependency. Containers
      would cost 23.0 MB across 13 jars and would leave the wire untested
      wherever Docker is absent, to avoid one import from `record.internal`.
      That import is the price of the coverage, and it is cheaper than the
      coverage is valuable.
- [ ] **`spec-0117-unhold`** — `kafka-clients` to 4.x with the comment in
      `proofload-kafka/build.gradle.kts` removed.
      Done when: `./gradlew build` is green on 4.x with no test skipped that was
      not skipped before.

## Acceptance

```bash
./gradlew :proofload-kafka:test
./gradlew build
```

## Open questions

1. **A or B?** Recommended: B, per Why this shape. It is the one that makes 0061
   worth having rather than leaving two specs half-done.
2. **If B, do the two tests survive as container tests, or are they lost where
   Docker is absent?** Recommended: they run where Docker is present and skip
   loudly elsewhere, which is 0061's own wording — but that means the wire
   protocol is untested on a runner without Docker, and that should be said out
   loud rather than discovered.
3. **Is `record.internal` acceptable in the meantime?** It is needed for entry 1
   under either option. Recommended: yes, with the comment that is already written
   at the call site, and gone entirely under B.
4. **Does anything else in the group bump also want holding?** Only these two of
   nineteen needed work, and `json-schema-validator` 3.x was migrated in #68.
   Recommended: no, but the next major in that group deserves the same check
   rather than the same surprise.
