# 0010 — Pelican

## Problem

A load test written against a service describes that service's HTTP a second
time: paths as strings, bodies as string literals, statuses as magic numbers.
It drifts from the real contract the day someone renames a path, and the load
test keeps passing against an endpoint nobody serves.

[Pelican](https://github.com/matthewjones372/pelican) already holds that
contract as a value: an endpoint description that yields the server route, the
OpenAPI document and a typed client. A team with Pelican descriptions should
not write their paths out again to load-test them.

## Not doing

- No Pekko. `pelican-client-pekko` is Pelican's own transport; putting an actor
  system inside a load generator adds a second scheduler for this tool to
  measure.
- No code generation and no Gradle plugin. This is a library module.
- No change to Pelican. If a change there turns out to be worth it, it is a
  spec in that repository, not a patch from this one.
- No OpenAPI import. Generating a smoke scenario from a document is a later
  spec, and a different idea.

## Shape

```kotlin
import io.github.matthewjones372.kestrel.pelican.kestrelTransport
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.scenario
import kotlin.time.Duration.Companion.minutes

val transport = kestrelTransport("https://orders.internal")
val orders = OrdersClient("https://orders.internal", JacksonCodecs, transport)

val checkout = scenario("checkout") {
    exec("placeOrder") { orders.placeOrder(1L, CreateOrder("anvil")) }
}

val simulation = checkout.at(50.perSecond, over = 1.minutes)
```

- `kestrel-pelican`, depending on `kestrel-core`, `kestrel-http` and
  `pelican-core`. Its dependency test asserts those and no Pekko.
- `kestrelTransport(baseUrl)` returns Pelican's `ClientTransport` —
  `send(ClientRequest): CompletionStage<ClientResponse>` — implemented over the
  same `java.net.http` client `kestrel-http` already builds.
- Every request that passes through it is **timed and recorded**, keyed by the
  endpoint's **path template** rather than the substituted URL.

## Why this shape

`ClientTransport` is a one-method interface in `pelican-core`, and Pekko lives
behind it in a separate module. That seam is the whole integration: implement
it, and a generated typed client runs unchanged inside a load test, on virtual
threads, with the declared failures of each endpoint still decoded into their
sealed types.

Keying on the path template is what keeps a report readable. `/orders/{id}` is
one row; the substituted URL is one row per order. It also means the rows are
named the way the service's own OpenAPI document names them.

## Stack

- [ ] **`spec-0010-transport`** — module, wiring, dependency test, and
      `kestrelTransport` sending a `ClientRequest` and returning a
      `ClientResponse`.
      Done when: a request through the transport reaches a JDK `HttpServer` and
      the response is decoded, with no Pekko on the classpath.
- [ ] **`spec-0010-recording`** — timing every request and recording it under a
      step name, with the failure reason taken from the status.
      Done when: two calls through the transport land in one `RunResult` row.
- [ ] **`spec-0010-templates`** — resolving the path template for a request by
      matching it against the endpoint descriptions the caller holds.
      Done when: ten calls to `/orders/{id}` with ten different ids produce one
      row named `/orders/{id}`, not ten rows.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

Answered by the architect:

1. **Templates are recovered by matching against `PathSpec` values the caller
    already holds**, not by asking Pelican to carry an `operationId` on
    `ClientRequest`. That keeps this a one-repository change. If the matching
    turns out to be hot, the Pelican change is the follow-up.
2. **Pelican is pinned to a released version** from Maven Central, not a
    composite build of a sibling checkout. A module that only compiles on one
    laptop is not a module.
3. **An unmatched URL is recorded under the request's method and its literal
    path with numeric segments replaced by `{}`**, so an unrecognised call is
    still one row rather than thousands.
