# 0069 — A transport the HTTP module can swap

## Problem

Every HTTP step goes through `exchange` in `Transport.kt`: one blocking send
per virtual thread through the module-level `sharedClient`. There is nowhere
else to put a client — `exchange` takes a `java.net.http.HttpRequest` and
`Response.of` an `HttpResponse<String>`, so the JDK's types are the module's
shape and a second client would be a fork of it.

0056 measured that path at **at least 2,500 a second** over a socket on four
loaded cores, 29,568 of 50,000 requests unanswered at 10,000. `wrk` and `k6`
publish an order of magnitude more per core, unmeasured here and not the point:
a user needing 20,000 a second cannot have it, and `fellBehind()` saying so
honestly is still not the rate. The JDK client is the right default, asserted by
`NoThirdPartyDependenciesTest`; it should not be the only one.

## Not doing

- **No Netty, OkHttp or Ktor in `kestrel-http` or core.** A seam, no dependency.
- **No fast transport here.** A spec of its own: a leaf module with a
  third-party or native client. Done when: it passes this spec's contract test,
  its classpath is core plus `kestrel-http` plus one client stack, and its
  ceiling, from 0056's sweep, is on `docs/what-it-costs.md` beside the JDK one.
- No connection-per-user transport, though the seam is what makes one sayable.
- No second engine. 0051's `Engine` is a different seam — see below.
- No HTTP/3. Named as a question.
- No change to what the JDK path does, measures or costs: same client, same
  `followRedirects(NEVER)`, same thirty-second default, same catch branches.
- No change to `kestrel-pelican`, which builds a JDK client of its own.
- No suspend or async shape. 0051 answered it: blocking is the point.

## Shape

```kotlin
/** One round trip. No redirects, no cookies, no retries, no clock. */
fun interface Transport {
    fun exchange(request: Request): Exchange
}

sealed interface Exchange {
    data class Answered(val response: Response) : Exchange
    data class Failed(val reason: Reason) : Exchange
}

val api = http.baseUrl("https://shop.internal")               // JdkHttpClient
val api = http.baseUrl("https://shop.internal").over(Netty()) // a leaf module's
```

`Request` is a value of method, URI, headers, body and timeout. Above the seam
stay the hop-by-hop redirect walk and `TooManyRedirects`, the per-user cookie
jar (0055), `traceparent` and `baggage` (0023) as headers, and 0063's reasons
read off the response. A transport owes `TimedOut` and `Threw(class)`, never
the message, and no more.

## Why this shape

One method, because a round trip is one thing: a wider interface would describe
the JDK client's internals rather than what a sender is, which is 0051's
argument for `Engine`. Kestrel's own values across it make a second
implementation a leaf module carrying its own dependency — 0010's pattern for
Pelican's `ClientTransport`, 0060's for `kafka-clients`. The failure is one of
those values, not the `StepScope` `exchange` takes today: a transport holding
the scope could write to the user's session, and first-reason-wins would be a
stranger's to keep. Nor does the seam carry a duration — service time is
measured by the engine in `Action.runOn` and response time from the intended
departure (0003, 0045), so a transport timing itself is a third clock.

One shared client stays the default, for the reason `Transport.kt` gives: a
client per user measures TLS handshakes, which 0005 said needs saying out loud
rather than in a flag, and `over(ConnectionPerUser())` is a caller saying it. A
transport also fixes something smaller: `sharedClient` is a module-level `lazy`,
so the first request in a JVM builds it *inside a measured sample*, and two runs
share a client neither owns.

This is not 0051's seam: an `Engine` runs a simulation — schedule, threads,
recorders, the exclusivity lock — chosen on `Kestrel` at run time, while a
`Transport` sends one request and is chosen on the `Http` value, a request being
described before any run exists. The alternative — a module reimplementing
`HttpAction`, the jar, the redirects and the trace headers — is 0005's forty
lines everybody writes, once per client.

## Stack

- [x] **`spec-0069-seam`** — the three types, and `JdkHttpClient`.
      Done when: an `HttpAction` sends through a recording double touching no
      socket, and the existing tests pass unchanged.
- [x] **`spec-0069-chosen`** — `Http.over(transport)`, carried like `cookies`.
      Done when: two `Http` values in one run send through two transports, with
      cookies, `following` and `traceparent` unchanged over a double.
- [x] **`spec-0069-contract`** — the contract a transport owes, as a test any
      implementation runs through against `com.sun.net.httpserver`.
      Done when: a 503 is `Answered`, a silent target `Failed(TimedOut)`, a
      refused connection `Failed(Threw("ConnectException"))`.
- [x] **`spec-0069-docs`** — the `docs/modules.md` row and the HTTP page.
      Done when: `ModulesDocTest` passes, and the page says what a transport
      must preserve and must not do.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **`String` or `ByteArray` body across the seam?** `Response.body` is a
    `String` every check reads, so decoding is on the timed path. Recommend
    `String` here, and measuring the decode once a second transport can.
2. **Where does the contract test live?** `src/testFixtures` needs a plugin
    nothing here uses; a testkit module is a published artifact for a test.
    Recommend fixtures, named unsupported surface.
3. **Does a transport need closing?** Nothing closes `sharedClient` today and
    `Http` has no lifecycle. Recommend no `close` on the seam; a transport with
    a pool to release exposes its own. Built that way.
6. **`Response` had to become constructible.** Found by writing the seam: its
    constructor was internal, so a transport in another module could not build
    what it must return — a seam that seams nothing. It now has a public
    constructor taking header pairs, which lowercases the names rather than
    trusting them, so a transport cannot change what `header("Location")`
    finds by casing its keys differently.
4. **Does connection-per-user land here?** Recommend a spec of its own: it is
    cheap once the seam exists, and needs the page that says what it measures.
5. **HTTP/3?** The JDK client does not speak it, so no configuration of
    `kestrel-http` ever will. Recommend naming it as something the seam allows
    and building nothing.
