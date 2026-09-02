# 0071 — gRPC steps

## Problem

HTTP, WebSocket and Pelican ship; none is what a Kotlin service talks to another
Kotlin service. A generated stub called inside `exec(name) { }` is timed like
anything else — the README calls gRPC "just a step body" — but not honestly:

- **Every failure is one row**: `VirtualThreads.kt`'s one permitted catch turns
  every `StatusRuntimeException` into one `Threw`, so 0063's question — target
  saying no, or socket giving up — cannot be asked.
- **No budget**: `Transport.kt` gives an HTTP request 30 seconds; a call with no
  deadline waits as long as the target likes.
- **No trace**: 0023's `traceparent` and synthetic `baggage` ride HTTP only.
- **The row is named whatever string was typed**, where 0005 names an HTTP row
  by its path template and 0010 recovers one for Pelican.
- **A stream is one sample**: a server-streaming call drained inside one `exec`
  measures N messages and the gaps between them together.

## Not doing

- **No code generation.** The caller runs protoc; this module never sees a
  `.proto`, a plugin or the protobuf runtime — its own tests use a
  `MethodDescriptor<String, String>` and a marshaller they write.
- **No transport.** `grpc-netty-shaded` and `grpc-okhttp` carry a thread model,
  and a caller with stubs has chosen one; `forTarget` finds it by service loader.
- **No coroutine bridge.** A `runBlocking` around a `CoroutineStub` puts a
  second scheduler under a run whose model is one virtual thread per user.
- **No server**, beyond `grpc-inprocess` on this module's own test classpath:
  whether a cluster is sized right needs the caller's cluster, as 0060 argued.
- No reflection, no channelz, no health checks, no xDS, no gRPC retry policy;
  0026 owns retries. Nothing in core: core learns no `io.grpc` type.

## Shape

```kotlin
val orders = grpc.target("orders.internal:8443").traced().deadline(2.seconds)
val stub = OrdersGrpc.newBlockingStub(orders.channel)   // the caller's own stub

val checkout = scenario("checkout") {
    exec(orders.call(OrdersGrpc.getPlaceOrderMethod()) { stub.placeOrder(anvil) })
    open(watch, orders.stream(OrdersGrpc.getWatchFillsMethod()) { stub.watch(all) })
    awaiting(fill, count = 100, within = 30.seconds)
}
```

- A leaf carrying `io.grpc:grpc-api` and nothing else of its own; the descriptor
  is passed, not inferred, so the step is named `descriptor.fullMethodName`.
- `failedWith(GrpcStatus(UNAVAILABLE))` reads a run back, as `HttpStatus(code)`
  does; `DEADLINE_EXCEEDED` is recorded as core's `TimedOut` instead.
- One `ClientInterceptor` carries `traced()`'s metadata and a default deadline
  where a call has none, so a caller's `withDeadlineAfter` still wins.
- Streaming splits as 0059 split a socket — `open` for the call starting,
  `awaiting` for the wait, `send` per message — and a step is one sample, so
  `awaiting(100)` measures a batch of a hundred rather than a message.

## Why this shape

Passing the descriptor is the difference from 0010: a Pelican call site is not a
step, so its transport recovers a name from templates, while a gRPC call site is
one and the descriptor already carries the name and the type. The action
measures, not the interceptor, which would double-count the step it sits inside
and write from gRPC's executor threads into a not-thread-safe `RunRecorder`.

**One channel for the run, not one per user**, on `Transport.kt`'s argument for
the shared `HttpClient`: a `ManagedChannel` is a thread-safe pool, and one per
user would measure TLS handshakes rather than the target. The cost is gRPC's
known trap — one channel is one subchannel, so a run hits one backend and HTTP/2
caps calls at `SETTINGS_MAX_CONCURRENT_STREAMS`: a generator's ceiling, read as
the target's, unless it is measured.

`grpc-api` alone, not `grpc-kotlin-stub`: the seam is under the stub, so a
caller's `CoroutineStub` is named, traced and bounded anyway, while the Kotlin
stub would put `kotlinx-coroutines-core-jvm` on every consumer's classpath.

## Stack

- [x] **`spec-0071-module`** — the module, its dependency test, `grpc.target`,
      the shared channel, `call(descriptor) { }`.
      Done when: a unary call on an in-process server is one row named
      `orders.v1.Orders/PlaceOrder`, a streaming descriptor fails at build time,
      and the runtime classpath is core, `grpc-api` and its jars — nothing else.
- [x] **`spec-0071-status`** — `GrpcStatus`, `DEADLINE_EXCEEDED` as `TimedOut`.
      Done when: `NOT_FOUND` reads back as `failedWith(GrpcStatus(NOT_FOUND))`,
      a refused connection as `GrpcStatus(UNAVAILABLE)`, a marshaller as `Threw`.
- [x] **`spec-0071-deadline`** — the interceptor's default deadline.
      Done when: a call with no deadline of its own is cancelled at the
      channel's and recorded as `TimedOut`, and `withDeadlineAfter` survives.
- [ ] **`spec-0071-traced`** — `traceparent` and `baggage` on outgoing metadata.
      Done when: an in-process server reads a well-formed `traceparent` off
      every call, ids differ per call, and an untraced target sends neither.
- [ ] **`spec-0071-streams`** — `stream`, `send` and `awaiting`, on `Pending`.
      Done when: a hundred answers to a hundred sends report a hundred matched
      and none outstanding, and a stream that stops early fails the wait.
- [ ] **`spec-0071-docs`** — the `docs/modules.md` row and a cookbook page.
      Done when: `ModulesDocTest` names the module, `smoke` resolves the
      coordinate, and the page says an `awaiting(n)` sample is a batch of n.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Is one channel enough to load a real deployment?** One channel is one
    backend, and `SETTINGS_MAX_CONCURRENT_STREAMS` caps calls on it. Recommend
    measuring it in `:benchmarks:ceiling`, and `channels(n)` only if it is not.
2. **`Status.Code` or the wire integer?** `HttpStatus(503)` is an int because an
    HTTP status is one; gRPC's canonical form is the enum. Recommend the enum.
3. **Does a per-call deadline belong on the action?** `Context.withDeadlineAfter`
    would give `call(…).deadline(…)` but wants a `ScheduledExecutorService` this
    module would own. Recommend the channel default and `withDeadlineAfter`.
    Built that way. The interceptor writes a deadline only where the
    `CallOptions` have none, so a caller's own survives untouched rather than
    being lengthened or shortened by a default they never asked for; a run that
    declared no budget writes nothing at all.
7. **What a caller builds a stub on.** `Grpc.channel` is the pool with this
    module's interceptor around it, and `Grpc.managed` is the pool itself, for
    shutting it down. A stub built on the raw pool gets no budget and no trace,
    which is why the intercepted one is the one named `channel`.
4. **Is `grpc-api` `api` or `compileOnly`?** Kotest is `compileOnly` in
    `kestrel-kotest` because a caller already has it, as one here has gRPC.
    Recommend `api`: the signatures are `MethodDescriptor` and `ManagedChannel`.
    Built `api`. What arrives with it is guava and five annotation jars, which
    `NoTransportTest` allows by name — they are `grpc-api`'s own and not a
    choice this module made; a transport, a protobuf runtime and coroutines are
    each refused separately, so the reason for each refusal survives.
5. **Is "transport failures to `Threw`" right?** Mostly not: a refused
    connection arrives as `Status.UNAVAILABLE`, not a `ConnectException`.
    Recommend `Threw` only for what escapes the status model, a marshaller.
    Confirmed against a real socket, which needed a transport on the *test*
    classpath — an in-process server has none to refuse. The module borrowing
    one to write that test is its own dependency claim demonstrating itself.
6. **`DEADLINE_EXCEEDED` under whose name?** Core's `TimedOut`, not a
    `GrpcStatus` of its own, so "how many timed out" has one answer across
    HTTP, WebSocket and gRPC rather than three spellings of it.
