# 0059 — An answer that streams

## Problem

Kestrel sends a request and waits for a response. A target whose interesting
behaviour is a stream — a price feed, a notification channel, a match engine
pushing fills — cannot be loaded at all, and the teams who most want an open
model and honest tails are often the ones running exactly those.

The gap is narrower than it looks. 0040 already built the hard half: a
departure whose answer arrives later and elsewhere, matched back to the
departure the profile promised, with records that never came counted apart from
records the run did not wait for. What is missing is a transport where the
"elsewhere" is a socket the user is already holding.

## Not doing

- No gRPC, no JMS, no MQTT. Each is a third-party dependency and a leaf module,
  and none of them is the one asked for first.
- No server-sent events in this spec. Same matching, different transport, and
  it is `HttpResponse.BodyHandlers.ofLines` — a second spec once this shape has
  been used in anger.
- No sub-protocols, no STOMP, no compression negotiation.
- No reconnection. A dropped connection is a finding, not something to paper
  over.

## Shape

A `kestrel-websocket` module on `java.net.http.WebSocket`, which is in the JDK,
so it carries no dependency — the same argument that made `kestrel-http` a thin
module over `java.net.http`.

```kotlin
val feed = ws.baseUrl("wss://prices.internal")

val watching = scenario("watching") {
    open(connect, feed.at("/stream"))
    send(subscribe, feed.text("""{"symbol":"ANVIL"}"""), keyedBy = { it[requestId] ?: 0L })
    awaiting(tick, count = 100, within = 30.seconds)
    close(disconnect)
}

result[connect].serviceTime.p99   // what the handshake took
result[tick].serviceTime.p99      // each message, from the send that provoked it
result[tick].unmatched            // sends nothing ever answered
```

- `open` is a timed step: one departure, one handshake, one sample.
- `send` is a departure that does not wait, correlated the way `emit` already
  is, and timed for the write alone.
- `awaiting` matches receipts to sends through the same `Pending` machinery
  0040 built, so `unmatched` and `inFlight` already mean the right things.
- A message nobody asked for is counted and not timed.

## Why this shape

The connection is one user's, so it is per user rather than pooled. That is the
opposite of `kestrel-http`'s shared client and for the opposite reason: a stream
test is *about* how many connections a target holds, so amortising the
handshake would remove the thing being measured. The cost is that file
descriptors bound the user count long before the scheduler does, and the module
says so rather than letting somebody find it at fifty thousand users.

An unsolicited message is counted and not timed because there is no departure
to subtract from it. Timing it from the connection's open would produce a
number that grows with the run's length and describes nothing; timing it from
the last send would attribute a server's own cadence to a request.

The profile's rate becomes a connection arrival rate, and the report has to say
so in those words. A reader who sees `2,000/s` on a page about a price feed
will read it as messages a second, and that is a misreading the page can
prevent for the cost of a line.

## Stack

- [ ] **`spec-0059-module`** — `kestrel-websocket`, its dependency test, `ws`,
      `open` and `close` as timed steps, one connection per user.
      Done when: the module asserts its own classpath, a handshake is one
      sample under the step's name, and a run closes every connection it made.
- [ ] **`spec-0059-send`** — `send` as a correlated departure, text and binary
      frames.
      Done when: a send is timed for the write and not for any answer, and
      carries its correlation.
- [ ] **`spec-0059-awaiting`** — receipts matched to sends, `unmatched` and
      `inFlight`, unsolicited messages counted.
      Done when: a hundred answers to a hundred sends report a hundred matched
      and none outstanding, and a server push with no send reports as
      unsolicited rather than as a match.
- [ ] **`spec-0059-report`** — the connection rate named as a connection rate.
      Done when: the page says the profile's rate opened connections, and
      prints messages a second separately.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Does `Completions` fit, or only `Pending`?** `Completions` is a run-level
    sink drained by one thread; a WebSocket's answers arrive per user on a
    socket that user owns. Recommend reusing `Pending` and `Outstanding` and
    *not* forcing this through `Completions`, and recommend checking whether
    the two want a common name once both exist rather than before.
2. **Does a message count towards `goodput` and the run's rate?** Recommend
    sends yes, receipts no, unsolicited receipts no — a rate is over departures
    and only sends are departures.
3. **What bounds the user count?** Recommend measuring it rather than guessing:
    a row in `:benchmarks:ceiling` for connections held, since 0056 is already
    adding a loopback target and a WebSocket one is the same shape.
4. **Is `awaiting(count)` the right verb, or should receipts be continuous?**
    A fixed count is testable and a stream is not naturally counted. Recommend
    `count` first and `awaiting(for = duration)` as a second form, because a
    soak on a feed wants "keep receiving for ten minutes" and cannot name a
    number.
