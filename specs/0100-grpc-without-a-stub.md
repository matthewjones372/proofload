# 0100 — gRPC without a stub

## Problem

0071 built gRPC steps over a caller's own stubs: `grpc.target(host).call(
MethodDescriptor, request)`. That is the right shape for Kotlin — the generated
stub is the contract, and a rename breaks the build.

It is unreachable from a plan file, and not by an oversight that can be patched.
A `MethodDescriptor` is a compiled object and the request is a lambda producing
a generated message class. A file cannot name either, and no amount of keys will
change that: the thing a plan would have to carry is code.

So a team whose service is gRPC can use everything in 0089, 0091 and 0092 for
their HTTP endpoints and none of it for the endpoints that matter. That is worse
than not supporting gRPC, because the tool looks like it covers the service and
covers half of it.

## Not doing

- **Not replacing 0071.** A caller with stubs should keep using them: it is
  typed, it is faster, and a rename breaks the build. This is for the caller who
  has a plan file and no stubs on the classpath.
- **No streaming.** Unary calls only. A bidirectional stream's latency is not
  one number and 0059 is where that argument already lives.
- **No proto compilation here.** Proofload does not run `protoc`.
- **No dynamic stub generation.** No classes are written at runtime.

## Shape

A step names a method and a payload as JSON:

```yaml
proofload:  plan/1
target:   orders.internal:9090
scenario: orders
steps:
  - name: place order
    call: shop.Orders/PlaceOrder
    body: '{"cart": "1 anvil"}'
    expecting: OK          # a gRPC status, not an HTTP one
    declared: [NOT_FOUND]
load:
  rate: 200/s
  over: 1m
```

To send that, something has to turn `shop.Orders/PlaceOrder` and a JSON object
into a serialised protobuf message. Two ways, and they are not alternatives so
much as a preference and a fallback:

- **Server reflection.** The target serves `grpc.reflection.v1alpha`, and the
  descriptors are fetched at run time. Nothing is needed from the caller.
  Recommend as the default: it is what `grpcurl` does and what a staging service
  usually already has on.
- **A descriptor set.** `descriptors: build/shop.protoset`, produced by
  `protoc --descriptor_set_out`. Deterministic, works against a target with
  reflection off, and needs a build step the caller runs.

Both land in `proofload-grpc-dynamic`, a leaf module carrying `protobuf-java`,
`protobuf-java-util` for the JSON, and `grpc-services` for reflection.

## Why this shape

Reflection first because it asks nothing of the caller, and a descriptor set
second because reflection is off in plenty of places and a benchmark that cannot
run against production-shaped config is a benchmark that gets run somewhere
else. Supporting only the descriptor set would be defensible and slower to
adopt; supporting only reflection would strand anyone whose security team
turned it off.

This is meaningfully more work than [0099](0099-kafka-in-a-plan.md), and the
spec says so rather than letting somebody discover it: Kafka is strings in a
file, and this is a message encoder. The honest ordering is Kafka first.

The alternative is refusing gRPC in plans and pointing callers at `emit
--kotlin`, which is what happens today by accident. Recommend against as a
final answer, but it is a perfectly good interim one, and if this spec is never
built that should be *written down* in `docs/` rather than left as a gap people
discover.

## Stack

- [x] **`spec-0100-descriptors`** — `proofload-grpc-dynamic`, its dependency test,
      and a descriptor set read into method descriptors.
      Done when: a `.protoset` yields a method by `package.Service/Method`, and
      an unknown method names the ones it has.
- [x] **`spec-0100-messages`** — JSON to `DynamicMessage` and back, using the
      declared input and output types.
      Done when: a request that does not match its schema is refused before the
      call, naming the field.
- [x] **`spec-0100-call`** — the step, over 0071's channel, with a gRPC status
      as the expectation and `declared` statuses beside it.
      Done when: a declared `NOT_FOUND` reports as declared and an undeclared
      `INTERNAL` does not.
- [x] **`spec-0100-reflection`** — descriptors fetched from the target instead.
      Done when: a target with reflection on needs no `descriptors` key, and one
      with it off says so rather than timing out.

Not in this stack and needed for the Problem above: **the plan keys**. Every
entry here builds the Kotlin — a schema, a message, a step, and descriptors
fetched rather than read — and `plan/1` still has no `call:`, so the caller this
spec was written for cannot yet write the file in **Shape**. The four boxes are
the library half. The file half is `target`, `call`, `body`, `expecting`,
`declared` and `descriptors` in `readPlan`, a `DeclaredStep.Call` beside 0099's
`Produce`, and a `Lowering` in a module that carries this one — which is 0099's
own shape and about the same size as its `spec-0099-reader`. It wants a spec
entry rather than an unannounced fifth branch.

## Acceptance

```bash
./gradlew build
```

## Open questions

- **Does this belong in `proofload-grpc` or beside it?** Recommend beside, as
  `proofload-grpc-dynamic`. `proofload-grpc` carries `grpc-api` and a caller's own
  stubs; protobuf's runtime, its JSON printer and the reflection service are a
  stack nobody wanting the typed path should inherit.
- **What is `expecting` when a gRPC call has no status code like HTTP's?**
  Recommend the status name — `OK`, `NOT_FOUND` — because that is what the
  generated code and every log line already call them, and a number would be a
  translation nobody asked for.
- **Is a JSON body the right thing to write in a plan?** Recommend yes, matching
  `grpcurl`, and note the cost plainly: a field renamed in the proto is a
  silently ignored key at run time rather than a compile error, which is exactly
  the drift the typed path exists to prevent. A plan buys convenience with the
  guarantee 0071 was written for.
