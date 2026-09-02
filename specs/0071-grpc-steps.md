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
- [x] **`spec-0071-traced`** — `traceparent` and `baggage` on outgoing metadata.
      Done when: an in-process server reads a well-formed `traceparent` off
      every call, ids differ per call, and an untraced target sends neither.
- [x] **`spec-0071-streams`** — `stream`, `send` and `awaiting`, on `Pending`.
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
10. **A step that samples and then fails records no failure.** Found by
    building this. The engine skips its own `sink.record` for a body that
    reported samples — right about the latency, since one sample over the whole
    body is a duration nobody experienced — but the *reason* rides that record,
    so the failure is dropped with it. A stream that died after ninety of a
    hundred answers reported ninety good samples and a clean step.
    Worked around here: the failed wait reports one sample of its own, timed
    from the last answer that did arrive — how long the one that never came had
    been outstanding, which is 0075's first open question answered the way it
    recommended. **`kestrel-websocket` has the same hole** and is not fixed
    here: 0059's `awaiting` reports its samples and calls `fail` the same way,
    so a socket that goes quiet part-way through a run reports no failure
    either. Recommend fixing it in core rather than in each module — a reason
    with no sample to ride needs a way to be counted — which is 0075's to
    argue.
11. **`grpc-stub` alongside `grpc-api`.** The spec said `grpc-api` and nothing
    else of gRPC's; a streaming seam has to speak `StreamObserver`, which lives
    in `grpc-stub` rather than in `grpc-api`. It adds nothing a gRPC caller
    does not already have — generated code depends on it — and none of the
    refusals move: still no transport, no protobuf runtime, no coroutines.
12. **Server streaming is not built.** `stream` matches each answer to the
    message it answers, which is the bidirectional and client-streaming shape.
    A server stream's messages answer no send of their own: timed from the call
    that opened it they climb with the index, and timed from each other they
    are cadence. Those are two different numbers and neither belongs in the
    same histogram as a round trip, so it wants its own verb naming which one
    it is — 0075's fifth open question, still open.
8. **Where the trace-id generator lives.** It was `internal` to
    `kestrel-http`, which would have meant a second copy here and a third in
    the next protocol module — two of them disagreeing about the format is the
    kind of difference nobody notices until a backend has half a run in it. It
    moved to core as `Traceparent`, beside `StepScope.traced`, which was
    already there. The http module keeps only the two header names.
9. **How the interceptor reaches the step.** A thread local, set for the length
    of the call and cleared after. An interceptor's signature is gRPC's, and a
    call made through a caller's own generated stub goes from the step body
    straight into the channel with nowhere to thread a scope through. A user
    runs on one virtual thread and gRPC starts a call on the calling thread, so
    the thread holding it is the one the interceptor runs on.
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
