# Protocols

Each protocol is a module that adds steps of its own kind. A scenario can mix
them: a journey can be an HTTP request, then a Kafka record, then a database
query. Add only the modules for the protocols you send; [modules.md](modules.md)
lists their dependencies.

What a protocol step times is part of what its numbers mean, so each section
says where the clock starts and stops. In every case response time is service
time plus the user's departure lateness ([measurement.md](measurement.md#what-exactly-is-inside-each-clock)).

| Protocol | Module | A sample covers |
|---|---|---|
| HTTP request | `proofload-http` | building and sending the request until the whole body is read, plus checks, captures and followed redirects; for a retry, the last attempt |
| Server-sent events | `proofload-http` | `open`: until the response head; `firstEvent`: request to first event; `cadence`: one sample per event, from the event before |
| WebSocket | `proofload-websocket` | `open`: the upgrade until 101; `send`: the write; `awaiting`: one sample per answer, from the send it answers; `close`: Close sent until the peer's Close arrives |
| gRPC unary | `proofload-grpc`, `proofload-grpc-dynamic` | the call through your stub, or the dynamic call |
| gRPC streams | `proofload-grpc` | per message, as for WebSocket and SSE |
| Kafka produce | `proofload-kafka` | the serializer and `send(...).get()`, as deep as `acks` |
| Kafka answer | `proofload-kafka` | from the user's scheduled departure to the answer being polled |
| JDBC statement | `proofload-jdbc` | prepare, execute and counting rows; the pool wait is recorded apart |
| your own client | `proofload-core` | the whole `exec(step) { ... }` block |

## HTTP

`proofload-http` sends requests on the JDK's `java.net.http` client, one client
shared by the whole run so that connection and TLS setup are not re-measured on
every request.

```kotlin
import io.github.matthewjones372.proofload.http.http

val api = http.baseUrl("https://orders.internal")

val checkout = scenario("checkout") {
    exec(browse, api.get("/products"))
    exec(placeOrder, api.post("/orders").header("content-type", "application/json").body("""{"cart":"1 anvil"}""").expecting(201))
}
```

`get`, `post`, `put`, `patch`, `delete` and `head` build a request. Without
`expecting(...)`, a request succeeds only on 200; any other status fails the
step as `HttpStatus(code)`. `declaring(404)` marks a status the target documents:
it still fails the step, but as `DeclaredStatus` so it can be told apart from
an undocumented one. The default timeout is 30 seconds, and a request that
exceeds it fails as `TimedOut`. Any other I/O failure is `Threw(class)`.

`{name}` in a path or body is filled per user from the session
([data.md](data.md)).

### Check the body, not just the status

A target under load answers 200 with an error page more often than people
expect.

```kotlin
exec(
    placeOrder,
    api.post("/orders")
        .body("""{"cart":"1 anvil"}""")
        .expecting(201)
        .checking("has an order id") { it.body.contains("\"id\"") }
        .timeout(Duration.ofSeconds(2)),   // java.time.Duration
)
```

A failed check fails the step with `CheckFailed("has an order id")` as its
reason. Checks and captures run inside the timed step.

### Cookies and redirects

```kotlin
val api = http.baseUrl("https://shop.internal").withCookies()

val signedIn = scenario("signed in") {
    exec(signIn, api.post("/session").body(credentials).expecting(302))
    exec(account, api.get("/account"))    // carries the cookie the sign-in set
}
```

The cookie jar is in each user's own session, not on the shared client, so
users do not share a login. It keeps name and value only: no expiry, path or
domain matching. A `cookie` header written in the scenario wins over the jar.
Cookies are off unless asked for.

Redirects are off unless asked for too: a 302 is the failure it was, under the
step that got it, until `following()` says otherwise:

```kotlin
exec(signIn, api.post("/session").body(credentials).following())
```

The hops happen in the step, not in the client, and each extra hop is counted
in `attempts`. Cookies set
by a 302 are carried to the page it points at. 301, 302 and 303 are followed as
a bodyless GET; 307 and 308 keep the method and body. `expecting()` judges the
response the chain lands on, so a sign-in that ends 200 is written
`.following()` rather than `.expecting(302)`. A chain longer than `max` fails
under `TooManyRedirects`.

### Retries

Real clients retry a 503. Proofload does too if you ask; it never retries by
default, because a target that fails one request in ten looks perfect behind two
retries:

```kotlin
exec(
    placeOrder,
    api.post("/orders")
        .expecting(201)
        .retrying(times = 2, on = { it.status == 503 }, backingOff = 100.milliseconds),
)
```

The step's latency is the **last** attempt's, and the earlier attempts and the
waits between them are counted rather than added to it:

```kotlin
result[placeOrder].count      // 480 requests
result[placeOrder].attempts   // 512 trips to the target
```

A retry written by hand inside a step body is timed as one long request, so its
p99 includes the backoff you chose.

**Known issue.** When every retry is used and the last answer still fails the
step (a wrong status, a failed check or capture), the recorded sample carries no
failure reason, so the request is counted as ok while the user's journey ends.
When the last attempt fails at the transport, no per-attempt sample is taken
and the whole step, earlier attempts and backoff included, is recorded. Until
this is fixed, read `failedWith` and `ok` counts on a retrying step with care.

### Large responses

A response is read into a `String` so a check can read it and a capture can take
values out of it. For a 200 MB export, that is 200 MB per user:

```kotlin
import io.github.matthewjones372.proofload.http.http

val api = http.baseUrl("https://api.example.com")

exec(download, api.get("/exports/{id}").discardingBody())
```

The bytes are counted as they arrive and let go. `Response.bytes` is what came
back and `Response.body` is empty. Check `bytes`: a download that returned 4 KB
instead of 200 MB reads as a very fast target.

`Response.bytes` is on every response, not only a discarded one. Where the body
was kept it is what that body encodes to as UTF-8 rather than a count taken off
the wire, which is the same number for a target that did not name another
charset.

**A check or a capture on a discarding step is refused when the scenario is
built**, because both read a body that will not exist.

A redirect is still followed (a hop reads `Location` from the headers, not from
the body) and so is a retry.

### Trace context

`traced()` puts a W3C `traceparent` and a synthetic-traffic `baggage` entry on
every request, so a slow measurement has a trace id to follow into whatever the
target exports its spans to.

```kotlin
val api = http.baseUrl("https://orders.internal").traced()
```

The `baggage` entry marks the traffic as synthetic, which is what lets the
receiving side keep it out of its own SLO dashboards. `traced()` and
`withCookies()` compose in either order.

### Another HTTP client

A `Transport` replaces the JDK client underneath the HTTP steps.
[what-it-costs.md](what-it-costs.md) shows what the default path sustained on
the machines it was measured on, and that it did not show the JDK client to be
the bottleneck.

```kotlin
val api = http.baseUrl("https://orders.internal").over(FasterClient())
```

A `Transport` is one method (a `Request` in, an `Exchange` out) and it lives
on the `Http` value, so a run against two services can speed up one and leave
the other alone.

The redirect walk, the per-user cookie jar, the `traceparent`, and the status
and body checks all stay above the transport, and it cannot write to a user's
session. It reports two kinds of failure, `TimedOut` and `Threw(class)`, and no
duration: service time is still measured by the engine around the step.

`TransportContractTest` in `proofload-http` is what an implementation is judged
against: a 503 is an answer and not a failure, a silent target is
`Failed(TimedOut)`, a refused connection is `Failed(Threw("ConnectException"))`,
and a redirect is never followed of the transport's own accord.

## Server-sent events

A plain GET at a stream endpoint measures only the moment the target agreed to
start talking, so a feed that opens instantly and then goes silent would read
like a busy one. An SSE stream is opened once and read with two verbs:

```kotlin
import io.github.matthewjones372.proofload.http.cadence
import io.github.matthewjones372.proofload.http.firstEvent
import io.github.matthewjones372.proofload.http.open
import io.github.matthewjones372.proofload.http.sse
import io.github.matthewjones372.proofload.http.stopReading

val fills = sse.baseUrl("https://feeds.internal").traced().at("/fills")

val watching = scenario("watching") {
    open(opened, fills)
    firstEvent(first, within = 5.seconds)
    cadence(each, count = 99, within = 60.seconds)
    stopReading(done)
}
```

`open` ends when the response head arrives, the target agreeing to stream, not
saying anything. `firstEvent` is the round trip to the first event, comparable
with a plain request's latency. `cadence` is one sample per event after it, each
measured from the event before: the rate the feed actually delivered at. A
hundred events is `firstEvent` plus `cadence(count = 99)`.

They are kept apart because timed from the request, the *k*th event's latency
grows with *k* and describes the feed's length rather than the target's speed.
A `cadence` before any `firstEvent` fails with `NoFirstEvent`. `open` accepts any
2xx status.

**A heartbeat is not an event.** A comment line (`:` and anything after it) is
counted on the stream and satisfies no wait:

```kotlin
session[eventStream]?.delivered    // events
session[eventStream]?.heartbeats   // comment lines, counted and never timed
```

A feed that only heartbeats times out.

**Nothing reconnects.** `retry:` and `Last-Event-ID` are ignored, and a far end
that lets go fails the waiting step with `StreamEnded`.

**Nothing is parsed.** Frames are read only far enough to find where one event
ends.

`stopReading` cancels the subscription and drops the connection. SSE has no
closing handshake, so its sample is the local cancel.

## WebSockets

Two handshakes, each its own timed step:

```kotlin
import io.github.matthewjones372.proofload.websocket.close
import io.github.matthewjones372.proofload.websocket.open
import io.github.matthewjones372.proofload.websocket.ws

val feed = ws.baseUrl("wss://prices.internal")
val connect = step("connect")
val disconnect = step("disconnect")

val streaming = scenario("streaming") {
    open(connect, feed.at("/prices"))
    pause(30.seconds)
    close(disconnect)
}

streaming.at(200.perSecond, over = 5.minutes)
```

`open` times the upgrade alone, from the request leaving to the server's 101
completing it. It says nothing about when data starts to flow.

`close` times what the target took to let go, not what the write took: the sample
runs from the Close frame being written to the far end's Close arriving back.

Between them, `send` and `awaiting` are two steps because they are two
questions:

```kotlin
import io.github.matthewjones372.proofload.Correlation
import io.github.matthewjones372.proofload.websocket.awaiting
import io.github.matthewjones372.proofload.websocket.send

val subscribe = step("subscribe")
val ticks = step("ticks")

val streaming = scenario("streaming") {
    open(connect, feed.at("/prices"))
    send(subscribe, feed.text("SUB ACME"), keyedBy = Correlation { it[order] ?: 0L })
    awaiting(ticks, count = 100, within = 30.seconds)
    close(disconnect)
}
```

`send` is timed for the write alone and waits for nothing; the sample ends when
the client reports the frame written. `awaiting` records **one sample per
answer**, each measured from the send it answers, so `awaiting(count = 100)` is
a hundred samples under one name and the report draws the messages' own
distribution. Keeping the two apart stops a slow target being reported as a
slow write.

Answers pair with sends in the order the sends left, which is the order one
socket delivers them in. A message that arrives with nothing outstanding is
counted rather than timed, and the counts read off the connection:

```kotlin
connection.matched
connection.unsolicited
connection.outstanding(within)   // Outstanding(unmatched, inFlight), as everywhere else
```

The wait is bounded and is signalled by the close as well as by an arrival, so a
far end that hangs up fails the step at once instead of parking a user for the
rest of the run.

The samples are placed on the timeline at the moment the wait finished, not at
the second each message arrived; the durations are each message's own. A wait
that times out records the answers that did arrive, and then fails.

There is one socket per user, because a stream test is usually about how many
connections a target holds. Expect file descriptors to bound your user count
before the scheduler does.

## gRPC

`proofload-grpc` calls through stubs you generated. Hand it the method descriptor
your generated code carries, and build your stub on the channel it gives you:

```kotlin
import io.github.matthewjones372.proofload.grpc.exec
import io.github.matthewjones372.proofload.grpc.grpc

val orders = grpc.target("orders.internal:8443").traced().deadline(2.seconds)
val stub = OrdersGrpc.newBlockingStub(orders.channel)

val checkout = scenario("checkout") {
    exec(orders.call(OrdersGrpc.getPlaceOrderMethod()) { stub.placeOrder(anvil) })
}
```

The row is named `orders.v1.Orders/PlaceOrder`, off the descriptor rather than
off a string you typed, so two call sites of one method are one row.

A status is a value the target sent, so it reads back by name:

```kotlin
result[placeOrder].failedWith(GrpcStatus(Status.Code.UNAVAILABLE))
result[placeOrder].failedWith(TimedOut)   // DEADLINE_EXCEEDED, under the one name every module uses
```

`deadline(...)` is the budget a call gets when it sets none of its own. Your
own `withDeadlineAfter` still wins. Other statuses fail as `GrpcStatus(code)`
and any other exception as `Threw(class)`. `call` accepts unary methods only;
streams have their own verbs below.

**One channel for the run, not one per user.** One per user would measure TLS
handshakes. The cost is that one channel can resolve to one subchannel, so a run
can land on one backend with HTTP/2 capping concurrent streams. That is a limit
on the generator side, not the target's; read `behind` and `ranOutOfRoom()`
before believing a gRPC ceiling.

**No transport here.** `grpc-netty-shaded` or `grpc-okhttp` is your choice and
already on your classpath with your stubs; `forTarget` finds it.

### A stream

```kotlin
open(orders.stream(OrdersGrpc.getChatMethod()) { answers -> stub.chat(answers) })
repeat(100) { send(each, message) }
awaiting(answers, count = 100, within = 30.seconds)
```

`awaiting(count = 100)` is a hundred samples under one name, each timed from
the message it answers, not one sample covering a hundred messages and the
gaps between them. An answer arriving with nothing outstanding is counted as
unsolicited and not timed: there is no departure to measure it from.

### A server stream

A server-streaming call sends one request and reads many answers, so it has two
verbs, as server-sent events do:

```kotlin
val fills = orders.serverStream(OrdersGrpc.getWatchFillsMethod()) { request, answers ->
    stub.watchFills(request, answers)
}

val watching = scenario("watching") {
    open(fills, request = symbol)
    firstAnswer(opened, within = 5.seconds)
    cadence(ticks, count = 99, within = 60.seconds)
}
```

`firstAnswer` is one sample, from the call being opened to the first message
arriving: a round trip, comparable with a unary call's latency. `cadence` is
one sample per message after that, each measured from the message before it:
the rate the target actually delivered at. A hundred messages is `firstAnswer`
plus `cadence(count = 99)`.

A `cadence` before any `firstAnswer` fails with `NoFirstAnswer`.

`send`, `awaiting` and `done` fail with `NotSending` here: the one request went
out with the call.

### Without generated stubs

`proofload-grpc-dynamic` makes unary calls with a JSON body, from a descriptor
set or from the server's reflection service, for when you have no generated
code:

```kotlin
import io.github.matthewjones372.proofload.grpc.dynamic.call
import io.github.matthewjones372.proofload.grpc.dynamic.descriptorSet
import io.github.matthewjones372.proofload.grpc.dynamic.exec
import io.github.matthewjones372.proofload.grpc.dynamic.reflected
import io.github.matthewjones372.proofload.grpc.grpc

val orders = grpc.target("orders.internal:8443")
val schema = descriptorSet(Path.of("build/descriptors/orders.pb"))   // or orders.reflected()

val checkout = scenario("checkout") {
    exec(orders.call(schema, "orders.v1.Orders/PlaceOrder", """{"sku":"anvil"}"""))
}
```

A call succeeds on `OK` unless you say otherwise with `expecting(Status.Code)`,
and `declaring(...)` marks documented statuses as for HTTP. Only unary calls are
supported; the module does not refuse a streaming method, it sends it as unary,
so check the method type yourself. A plan file cannot name a gRPC call.

## Kafka

A broker acknowledging a record is not a consumer having done the work. A
produce is an `emit`, and the answer read off another topic is a `completing`,
the shape [work that finishes somewhere else](#work-that-finishes-somewhere-else)
describes:

```kotlin
import io.github.matthewjones372.proofload.completing
import io.github.matthewjones372.proofload.kafka.Acks
import io.github.matthewjones372.proofload.kafka.Header
import io.github.matthewjones372.proofload.kafka.completions
import io.github.matthewjones372.proofload.kafka.emit
import io.github.matthewjones372.proofload.kafka.kafka

val broker = kafka.brokers("localhost:9092").acks(Acks.All)

val trades = scenario("trades") {
    emit(
        submitted,
        broker.topic("trades")
            .keyed { it[account]?.toString()?.toByteArray() }
            .value { avro.serialize(it[trade]) }        // your serializer
            .correlatedBy(Header("trade-id")),
        keyedBy = { it[account] ?: 0L },
    )
}

trades.at(5_000.perSecond, over = 5.minutes)
    .completing(
        settled,
        from = broker.topic("settlements").correlatedBy(Header("trade-id")).completions(),
        drainingFor = 30.seconds,
    )
```

The correlation is stated once, at the `emit`, and goes to both the departure
the run counts and the header the record carries. The completion side reads
that header and never touches the payload, which is why it needs no
deserializer, and why no schema registry is involved in reading an answer.

`submitted` times the serializer and the broker's acknowledgement, as deep as
`acks` makes it: an in-sync-replica round trip at `Acks.All` (the default) and
nothing at `Acks.None`. `settled` is measured from the user's scheduled
departure to the answer being polled, so it includes the generator's lateness
and every earlier step of the journey. The consumer joins with a fresh group id
at the latest offset and commits nothing. `drainingFor` is required.

`produce(step, topic)` is a produce step with no completion.

**No serializer and no schema registry are included.** Your serializer is a
`(Session) -> ByteArray?` lambda; a null value fails the step as
`NothingToSend`. A broker error fails it as `BrokerRefused(class)`.

### What a schema lookup costs, and where it shows

A registry-backed serializer fetches a schema once per subject and caches it,
so the first record pays an HTTP round trip. Because the cache is shared, any
record departing while that fetch is in flight waits behind it.

That cost lands in **the produce step's own latency**, because the lambda runs
inside the step. It does not land in `behind`: each user runs on its own
thread, so one blocked in a serializer delays nobody else's departure. Do not
read that first spike as the broker being slow.

Read `result.steady` for the run without it; that is what the steady segment
is for.

### Two more things worth knowing

`linger.ms` is set to 0 unless you say otherwise. A producer that lingers turns
an evenly spaced departure stream into bursts at the broker, so the arrivals
figure would report a smoothness the broker never saw. If you raise it, that
figure describes the injector rather than the broker.

When the producer's buffer fills, `send` blocks for up to `max.block.ms` on the
user's own thread. That wait is inside the produce step's latency, like the
schema lookup above. It is not reported as generator lateness.

Plans can produce to Kafka too ([plans.md](plans.md#kafka-steps)), with fixed
bodies and the user number as the correlation id.

## JDBC

`proofload-jdbc` sends statements over a `DataSource` you hand in, with your own
driver and pool:

```kotlin
import io.github.matthewjones372.proofload.jdbc.exec
import io.github.matthewjones372.proofload.jdbc.jdbc
import io.github.matthewjones372.proofload.jdbc.rows
import io.github.matthewjones372.proofload.jdbc.waitedForPool
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.sessionKey
import io.github.matthewjones372.proofload.step

val orderId = sessionKey<Int>("orderId")
val byId = step("select an order")

val orders = jdbc.on(dataSource)

val reading = scenario("reading") {
    exec(byId, orders.query("select id, sku from orders where id = ?").binding { listOf(it[orderId]) })
}
```

```kotlin
dependencies {
    testImplementation("io.github.matthewjones372:proofload-jdbc:0.1.0-rc4")
    // The driver and the pool are yours, and are the point: this module carries
    // neither, so a run measures the pool your service actually runs.
    testImplementation("org.postgresql:postgresql:42.7.4")
    testImplementation("com.zaxxer:HikariCP:6.2.1")
}
```

**The pool wait is its own number, and it is what the module is for.**

```kotlin
result[byId].serviceTime.p99   // what the database took
result[byId].waitedForPool.p99 // what your users spent queueing for a connection
result[byId].rows              // rows the query returned, counted
```

Service time covers preparing and executing the statement and counting the
rows. The time spent waiting for a connection from the pool is recorded
separately and is in neither clock. A hand-written `exec(name) { }` with your
own JDBC call inside would time the checkout and the query together. With them
apart, a report can say the database answered in 3 ms while users waited
400 ms for a connection.

`binding { }` reads the session, so a run is not ten thousand identical selects
measuring a query cache. It takes the parameters in the order the `?`s appear.

**Rows are counted, never read into objects**, so the measurement does not
include object mapping. To use a value from the result, write the call yourself
in `exec(name) { }`.

**A failure is a SQLSTATE, not a stack trace.** A deadlock, a unique violation
and a syntax error are three rows in a report under `SqlState`, and one row
saying `SQLException` under a class name. A driver that names no SQLSTATE gets
`Threw(class)`, and a driver-declared timeout gets `TimedOut`.

**A word about carrier pinning.** JDBC is blocking, and each user runs on its
own virtual thread. JDK 21 pins the carrier for the duration of a `synchronized`
block, which several drivers still use on their hot paths; a run that pins is a
run whose concurrency is capped at the carrier pool rather than at the database.
The PostgreSQL driver from 42.7 and the newer MariaDB and MySQL drivers unmount
cleanly; older ones and several commercial drivers do not. A pinned carrier is inside service time and is not detected
([measurement.md](measurement.md#what-a-run-does-not-establish)). Run with
`-Djdk.tracePinnedThreads=short` once to see whether yours pins.

**No transactions across steps, and no batches.** Each step takes and returns
its own connection.

## Pelican

`proofload-pelican` provides a Pelican `ClientTransport` on the JDK HTTP client,
so a client generated by [Pelican](https://github.com/matthewjones372/pelican)
runs inside a load test without Pekko. Each endpoint's declared failures are
still decoded into Pelican's own types.

Call the generated client from a step body, which the engine times like any
other step:

```kotlin
import io.github.matthewjones372.proofload.pelican.proofloadTransport

val transport = proofloadTransport(templates = listOf("/orders/{id}"))
// `orders` stands for your generated Pelican client, built on `transport`.

val reading = scenario("reading") {
    exec(readOrder) {
        val outcome = orders.get(OrderId(42))
        // Map the endpoint's declared failures to a failed step, as for any step body.
        if (outcome.isFailure) fail("order lookup failed")
    }
}
```

Passed a `RunRecorder`, the transport also records every exchange under
`METHOD /template` (numeric path segments become `{}` where no template
matches), with no departure lateness. That records each HTTP exchange inside a
step separately from the step itself; it is not connected to the engine's own
recording.

## Your own client

`exec` takes a block as readily as a request, and the whole block is timed.
Use it for a cache, a queue, or any client you already have.

```kotlin
val settle = step("settle")

val ledger = scenario("ledger") {
    exec(settle) {
        val account = this[customer] ?: return@exec fail("no customer fed")
        val outcome = ledgerClient.settle(account)
        if (!outcome.ok) fail(outcome.reason)
    }
}
```

`fail` marks the step failed with a reason; it does not throw. The first reason
wins. An exception thrown from the block fails the step as `Threw(class)`.
Everything in the block is inside the step's service time, including work that
is not a call to the target, so keep preparation out of it: use a feeder or
[`refreshing`](data.md#a-token-that-expires-mid-run) for values that can be
made in advance.

## Work that finishes somewhere else

Some systems answer on another topic, another queue or a webhook. The latency
that matters is from the user's scheduled departure to the answer arriving, not
the acknowledgement. Record the answer under a step of its own, so the
acknowledgement and the round trip are separate rows:

```kotlin
import io.github.matthewjones372.proofload.Correlation
import io.github.matthewjones372.proofload.InMemoryCompletions
import io.github.matthewjones372.proofload.completing

val tradeId = sessionKey<Long>("tradeId")
val submitted = step("submitted")
val settled = step("settled")

val settlements = InMemoryCompletions()

val trades = scenario("trades") {
    emit(
        submitted,
        action { broker.publish(this[tradeId]!!) },
        keyedBy = Correlation { session -> session[tradeId] ?: 0L },
    )
}

val result = proofload.run(
    trades.at(5_000.perSecond, over = 5.minutes)
        .completing(settled, from = settlements, drainingFor = 30.seconds),
)

result.unmatched   // departed, and never answered for
result.inFlight    // departed, and the run ended before the window closed
```

`unmatched` is work that had the whole drain window and was never answered;
`inFlight` is work that departed too late to have had it. Keeping them apart
stops a short drain window reading as a broken pipeline.

`InMemoryCompletions` is for a channel no module covers: call `observe(id)` from
wherever your consumer runs. Kafka has its own source, `completions()`, above.
