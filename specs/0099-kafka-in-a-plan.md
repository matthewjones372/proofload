# 0099 — Kafka in a plan

## Problem

`plan/1` sends HTTP and nothing else. A team whose hot path is a topic — an
order accepted on a queue and answered on another — can describe none of it
without writing Kotlin, which is the compile step 0089 exists to remove.

The library already does the work. 0060 built produce steps and the answer read
off a second topic by a correlation header, and 0061 measured what the adapter
costs. What is missing is the sentence in a file that says which broker, which
topic, and what to send.

Kafka is the tractable half of this. `kafka.brokers("host:9092").topic("orders")`
is strings the whole way down, and a producer's settings are already a map of
string to string — a plan can carry them without inventing anything.

## Not doing

- **No broker in the box.** The broker is the caller's, as the `DataSource` is
  in 0083 and the channel is in 0071. A plan names one; it does not start one.
- **No consumer groups managed here.** A completion reads a topic the caller
  names, with the group they name, and nothing cleans up after it.
- **No schema registry, no Avro.** A payload is bytes or a string. A registry is
  a second service and a second failure mode.
- **No gRPC.** [0100](0100-grpc-without-a-stub.md) — a different problem with a
  different answer.
- **No `from_openapi` equivalent.** There is no document to read a topic layout
  out of, and AsyncAPI is a spec of its own if anybody wants one.

## Shape

A step is a topic instead of a path, and everything else about a plan is
unchanged:

```yaml
kestrel:  plan/1
brokers:  localhost:9092
scenario: orders
steps:
  - name: place order
    produce: orders
    key:     "{orderId}"
    body:    '{"cart":"1 anvil"}'
    settings:
      acks: all
  - name: confirmed
    completes: place order
    on:        order-confirmations
    by:        correlation-id
    group:     kestrel-bench
    within:    30s
load:
  rate: 500/s
  over: 1m
goals:
  - step: confirmed
    p99:  2s
```

`produce` makes a step; `completes` makes the other half of one — the latency it
records is the round trip to the answer on the second topic, which is 0040's
`emit`/`completing` pair and is the number anybody benchmarking a queue is
actually asking for.

A plan may mix HTTP and Kafka steps: `baseUrl` and `brokers` are both optional
and a step names which it is by which key it carries.

## Why this shape

Kafka's own vocabulary rather than a translation of HTTP's. `produce` and
`completes` are what the log calls them, and a `post:` to a topic would read
like an endpoint that is not there.

The completion is declared on the step it answers rather than as a free-standing
step, because a completion that names no producer is a subscription with a
latency nobody can attribute. `within` is required for the same reason 0040
requires it: a run that waits forever for an answer that never comes reports no
failure and no number.

The alternative is a `kafka:` block listing everything, with steps referring
into it. Recommend against: it puts the broker and the topic two places apart
from the step that uses them, which is the arrangement 0089 rejected for
captures.

## Stack

- [x] **`spec-0099-model`** — `produce` and its keys in the plan model, lowering
      to `kestrel-kafka`'s producer step.
      Done when: a declared produce step and the equivalent Kotlin build equal
      scenarios, against a fake broker on a socket.
      Built as two branches, `spec-0099-model` and `spec-0099-lowering`. The
      lowering does not live in `kestrel-plan`: doing it as written would put
      `kafka-clients` on the classpath of everyone reading a plan of nothing but
      requests, so `kestrel-plan` declares a `Lowering` and `kestrel-plan-kafka`
      supplies one. `kestrel-cli` and `kestrel-mcp` carry that module.
- [x] **`spec-0099-completes`** — the answer on another topic, and `within`.
      Done when: a plan whose answer never arrives reports the records that
      never came rather than hanging.
- [x] **`spec-0099-reader`** — the keys in `readPlan`, the writer, and `emit`.
      Done when: a Kafka plan round-trips through `asYaml` and emits Kotlin that
      compiles.
- [x] **`spec-0099-mcp`** — `benchmark` accepting a plan with brokers in it, and
      the questions it should ask about one.
      Done when: `preview` names the broker as the host it would reach, so 0088
      fences a topic the way it fences a URL.

## Acceptance

```bash
./gradlew build
```

## Open questions

- **Does `preview` treat a broker as a host?** Recommend yes, and it matters: an
  allowance that fences HTTP and waves through a producer is a fence with a
  hole in the shape of the thing most likely to be shared infrastructure.
- **Answered: `preview` treats a broker as a host**, and every entry of the
  bootstrap list rather than the first — which needed a `hosts` beside
  `Targeted.host`.
- **Unanswered by this spec and decided while building it: a plan has no
  session interpolation.** The `key: "{orderId}"` above reads as a literal.
  Nothing in `plan/1` substitutes a session value at run time — the contract
  importer fills paths when it *generates* a plan — so every record from one
  step carries the same key and the same body. Said in `plan_schema` and in
  `docs/mcp.md`, and `emit` plus a feeder is the way out. Adding interpolation
  would be a change to the whole format and wants a spec of its own.
- **Is the payload a string or bytes?** Recommend a string in the file, with the
  bytes taken as UTF-8, and a `bodyFrom` naming a file for anything else. A plan
  full of base64 is a plan nobody reads.
- **Does a completion step appear as its own row in the report?** Recommend yes:
  it has its own latency and its own failures, and folding it into the producer
  would report a round trip as though it were a publish.
