# 0078 — Server-sent events

## Problem

A team whose product streams — a dashboard, a notification feed, an LLM
answering token by token — has nothing here. `kestrel-http` measures one round
trip (`HttpAction.kt`), which for a feed is the moment the headers arrive and
nothing after it. `kestrel-websocket` speaks a different protocol over a
different handshake, so a team on SSE cannot borrow it.

What they do instead is send a plain GET at the stream endpoint and read the
p99 of the response headers, which says how fast the target agreed to start
talking and nothing about whether it kept talking. A feed that opens instantly
and then goes silent is indistinguishable on that page from one delivering
sixty events a second.

## Not doing

- **No reconnection.** `retry:` and `Last-Event-ID` are ignored. A generator
  that reconnects hides the disconnection it exists to report, and the second
  connection is a second experiment: `Disconnected` is a finding.
- **No `Transport` change.** That seam is one round trip on purpose (0069), and
  widening it to carry a stream would describe the client's internals. A
  stream gets its own narrow seam or none.
- **No parsing of `data:` payloads.** Bytes counted, never read: 0060 refused
  the same thing for Kafka, and a JSON parser on the timed path is a
  measurement of Jackson.
- **No event-type filter.** `event: fill` is carried on the event and counted;
  waiting for one kind rather than another is a scenario's job, not a step's.
- **No new module.** SSE is HTTP over the JDK client with no dependency of its
  own, so a module would be publishing scaffolding buying no isolation.

## Shape

```kotlin
val ticks = sse.baseUrl("https://feeds.internal").at("/fills")

val watching = scenario("watching") {
    open(opened, ticks)
    firstEvent(first, within = 5.seconds)
    cadence(each, count = 99, within = 60.seconds)
    stopReading(done)
}
```

- `open` is the request and the response headers: one sample, ending when the
  target agrees to stream. No event has been asked for at that point.
- `firstEvent` is the round trip to the first event, measured from the open.
- `cadence` is one sample per event after it, each from the event before —
  0071's split, for the same reason and under the same names.
- `stopReading` cancels the subscription. Not `close`: SSE negotiates no
  closing handshake, so there is nothing to send and nothing to wait for, and
  spelling it `close` beside the websocket verb that does both would promise
  one. Its sample is the cancel, which is local and near-instant, and it says
  so where it is written.
- A comment line (`:heartbeat`) is counted on the stream and is not an event:
  it satisfies no wait and lands in no histogram. A target that only heartbeats
  reports a timeout, which is what it earned.

## Why this shape

**The same two numbers as a gRPC server stream.** An SSE event answers no send
of its own, so timing it from the request climbs with the index and timing it
from the event before is cadence. 0071 settled that argument and 0071's verbs
are the ones a reader has already met; a second spelling of one idea is drift.

**Lines through a subscriber, not a blocking stream.**
`BodyHandlers.ofLines()` gives a `Stream<String>` that cannot be polled to a
deadline, so a quiet target would park a user forever. `fromLineSubscriber`
hands lines to a subscriber on the client's thread, which parses frames and
queues them; the user's own virtual thread waits on the queue with a deadline
and unmounts while it does. That is the arrangement `Inbound` and `Answers`
already use, for the same reason.

**Counted, not kept.** The fields are read only far enough to find where one
event ends and the next begins — a blank line after a `data:` — and then
dropped. Materialising an event per frame is an allocation per event for
something no step in this spec reads; a verb that captures one can add it when
somebody wants it (question 4).

## Stack

- [x] **`spec-0078-stream`** — `sse`, `open`, `close`, the frame parser and the
      per-user stream.
      Done when: a feed of ten events opens, is read to the end and stops; a
      comment line satisfies no wait and is counted separately; and an `open`
      against a target that refuses is a failure rather than a throw.
- [x] **`spec-0078-reading`** — `firstEvent` and `cadence`, on the split 0071
      settled.
      Done when: a feed that pauses before its first event and then delivers
      the rest at once reports one long round trip and short gaps, a `cadence`
      before any `firstEvent` is refused, and a stream that stops part-way
      reports the events that arrived plus one failure.
      **Landed with the entry above, not after it.** Splitting them was
      possible — `EventStream.delivered` observes the parser without the
      reading verbs — and it was not done, so the two are one change of about
      four hundred lines rather than two of two hundred. Recorded rather than
      tidied away.
- [x] **`spec-0078-docs`** — the cookbook page and the `docs/modules.md` note.
      Done when: the page says what each verb measures and that nothing
      reconnects.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Does `open` belong to `kestrel-http`'s cookie jar and `traceparent`?**
   Both are on `Http` and an SSE request is an HTTP request. Recommend yes for
   `traced`, which is the same trace a reader would follow, and leaving cookies
   until somebody has a feed behind a session.
2. **What closes a stream the scenario never closes?** A user that ends without
   `close` leaves the response body open until the client collects it.
   Recommend the run's own teardown cancelling what is left, and counting it,
   rather than a finaliser nobody can see.
3. **Should an event's content be readable?** Nothing here keeps it, so a
    scenario cannot assert on what arrived. Recommend leaving it until a
    `capture`-shaped verb wants it: the shape of that verb decides whether the
    frame is kept, and guessing now costs an allocation per event for nobody.
4. **`cadence` collides with `kestrel-grpc`'s.** Same name, same signature,
    same meaning, two packages — a scenario measuring both a gRPC server stream
    and an SSE feed has to alias one on import. Recommend leaving it: the names
    agree because the numbers do, and renaming one to dodge a rare import would
    make two names for one idea.
5. **Should bytes be counted per event?** A feed's cost is bytes as much as
   events, and the JDK gives the line lengths for free. Recommend counting them
   on the stream, not sampling them: a byte count is not a duration and has no
   business in a histogram of one.
