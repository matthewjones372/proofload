# 0101 — A method in a plan

## Problem

[0100](0100-grpc-without-a-stub.md) argued that a team whose service is gRPC can
use everything in 0089, 0091 and 0092 for their HTTP endpoints and none of it
for the endpoints that matter. Its four entries built the library half and none
added a key to `plan/1`. So that caller can now make the call from Kotlin —
which is where 0071 already left them — and still cannot write the file in
0100's own **Shape**.

That gap is the argument, not a tail of it. What is missing is 0099's shape
again for a different protocol: a step kind, its keys in the reader and writer,
a lowering in a module that carries the client, and a fence that can see where
the call goes.

## Not doing

- **No second way to describe a call.** `DeclaredStep.Call` beside `Request` and
  `Produce`, through the same reader and writer — not a `grpc:` block with steps
  referring into it, which 0089 rejected for captures and 0099 for brokers.
- **No streaming.** Unary only: 0100's refusal, and 0059's argument.
- **No `from_proto`.** A descriptor set describes types; what rate to send at is
  not in it. `benchmark` taking a `descriptors` path is the generation worth
  having.
- **No third module.** The lowering goes in `kestrel-plan-grpc`, beside
  `kestrel-plan-kafka`, for the reason that one exists.

## Shape

0100's **Shape**, read and lowered:

```yaml
kestrel:     plan/1
target:      orders.internal:9090
descriptors: build/shop.protoset   # optional: reflection where absent
scenario:    orders
steps:
  - name: place order
    call: shop.Orders/PlaceOrder
    body: '{"cart": "1 anvil"}'
    expecting: OK
    declared: [NOT_FOUND]
load:
  rate: 200/s
  over: 1m
```

`target` joins `baseUrl` and `brokers`, all three optional and needed only by
the steps that use them. Absent `descriptors` means ask the target, which is
0100's default and asks nothing of the caller.

## Why this shape

`call` rather than a verb key: it is what 0100 sketched and what `grpcurl` calls
it, and it separates a step whose value is a method from one whose value is a
path. `expecting: OK` is a name for 0100's reason — that is what the generated
code and every log line say.

Descriptors are read where the plan is lowered, not at the first departure, so a
mistyped method and a body that does not fit are refused while the file can
still be corrected. The alternative is naming a `.proto` and running `protoc`.
Recommend against: 0100 refuses it in a line, and a load generator that shells
out to a compiler is a build system nobody asked for.

## Stack

- [ ] **`spec-0101-model`** — `DeclaredStep.Call`, `target` and `descriptors`,
      the writer, and the emitter branch the sealed type forces.
      Done when: a call step round-trips through `asYaml`, and `asSimulation`
      refuses one naming the module that lowers it.
- [ ] **`spec-0101-reader`** — the keys in `readPlan`, and `emit`.
      Done when: 0100's **Shape** block parses, and the Kotlin it emits is
      checked in under `examples` and compiled by the build.
- [ ] **`spec-0101-lowering`** — `kestrel-plan-grpc`, its dependency test, and
      the `Lowering` over 0100's `DynamicCall`.
      Done when: a declared call and the equivalent Kotlin build equal scenarios
      against the in-process server, and a body that does not fit is refused
      with the plan rather than at a departure.
- [ ] **`spec-0101-fence`** — `DynamicCall` as `Targeted`, and what `benchmark`
      should ask about a call.
      Done when: `preview` names the target as the host it would reach, so 0088
      fences a method the way it fences a URL.

## Acceptance

```bash
./gradlew build
```

## Open questions

- **Does `kestrel-cli` carry `kestrel-plan-grpc`?** Recommend yes, on 0099's
  argument: refusing a method from the tools that read plans leaves the format
  half-usable from the tools it exists for. The cost is plain — `grpc-services`
  brought six jars, so they land on the command line too.
- **Is `descriptors` a path, or may it be inline?** Recommend a path. A plan
  full of base64 is a plan nobody reads, which is 0099's answer for a payload.
- **May a plan pick the reflection version?** Recommend no: a key whose only use
  is working around a bug in 0100's `v1`-then-`v1alpha` order.
- **What host does `preview` report — with the port, or without?** Recommend
  without, matching HTTP, so one allowance entry fences a service reached over
  both.
