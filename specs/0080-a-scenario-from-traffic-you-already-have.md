# 0080 — A scenario from traffic you already have

## Problem

Every scenario in this repository was typed by hand. That is the main cost of
adopting any load tool and the one Kestrel does nothing about: a team with a
working checkout flow has to read it back out of their own code, guess which
values are per-user, and write the captures that thread them together
(`docs/cookbook.md`, "Chain two steps with a capture"). Gatling ships a
recorder and k6 ships `har2k6`; here the first hour is transcription.

The transcription is also where the mistakes are. A hand-written scenario omits
the header that mattered, hard-codes the id that should have been captured, and
sends one body every user shares — the last of which 0079 has only just made
avoidable. None of those show up as a failure. They show up as a number that is
about a lighter experiment than the one somebody meant to run.

## Not doing

- **No proxy, no browser, no certificate.** Recording traffic is a solved
  problem with several tools that already do it; a HAR file is what they all
  export. This reads the file.
- **No runtime scenario.** The output is Kotlin source a person edits and
  commits, not a `Scenario` built from a file at run time. A recording is a
  first draft — most of it wants deleting — and a draft that is re-read on
  every run is one nobody edits.
- **No replay of a HAR's timings.** 0072 replays an arrival process from a
  capture and does it properly. Wall-clock gaps between browser requests are
  one person's think time, not a rate line.
- **No static assets by default.** A page load is forty requests to a CDN and
  one to the API. Including them measures somebody else's cache.
- **No guessing at auth.** A token in a recording is a token that has expired;
  the cookbook already has the recipe for refreshing one off the measured path.

## Shape

```bash
./gradlew :kestrel-record:run --args="checkout.har --package com.acme.load --out src/test/kotlin"
```

```kotlin
// generated — edit it, commit it, delete most of it
val orderId = sessionKey<String>("orderId")

val checkout = scenario("checkout") {
    exec(step("POST /session"), api.post("/session").body("""{"user":"{user}"}""").expecting(200))
    exec(
        step("POST /orders"),
        api.post("/orders")
            .body("""{"cart":"1 anvil"}""")
            .expecting(201)
            .capture(orderId) { it.body.between("\"id\":\"", "\"") },
    )
    exec(step("GET /orders/{orderId}"), api.get("/orders/{orderId}"))
}
```

- One `exec` per recorded request, named `METHOD /path`, in the order they were
  made. A run of identical paths differing only in a segment becomes one step
  with a `{name}` in it and a comment saying how many it stood for.
- A value that appears in one response and then in a later request becomes a
  `capture` and a `{name}`, which is the whole point: that chain is what a
  hand-written scenario gets wrong.
- **Every header and cookie that looks like a credential is redacted**, to a
  `TODO("...")` naming what was dropped. A recording is full of live tokens and
  a generator that wrote them into source would put them in somebody's git
  history. That is the default and there is no flag to turn it off.
- `--include`/`--exclude` on the path, defaulting to excluding the extensions a
  browser fetches without being asked.

## Why this shape

**A file, not a proxy.** A proxy means a CA certificate, a browser
configuration and a man-in-the-middle on somebody's laptop. Chrome, Firefox,
Charles, mitmproxy and every API client already export HAR. Reading the file is
one hundredth of the work and none of the trust.

**Source, not a scenario.** A generated `Scenario` object would be re-read on
every run, so the forty CDN requests and the expired token stay in it forever
and the thing nobody does is edit it. Source is reviewable in a pull request,
diffable when the flow changes, and mostly deletable — which is what a reader
does with a first draft and cannot do with a file.

**A module with a JSON parser, and that is consistent.** A HAR is JSON and
this repository takes no dependencies lightly. But the rule it actually holds
to is *per module*: `kestrel-kafka` carries `kafka-clients`, `kestrel-otel`
carries the OTel SDK, and `kestrel-core` carries nothing. This is another such
module, and it is further from the timed path than either — it runs before a
run rather than during one. The `V2Encoding.kt` precedent for hand-rolling does
not apply: that is a few hundred bytes of a written-down binary format, and
this is arbitrary JSON out of a browser.

**Redaction is not a flag.** A `--include-secrets` switch is one somebody sets
once and forgets, and the failure is a token in a public repository. The
generated source names what was dropped and where to put it back.

## Stack

- [x] **`spec-0080-module`** — `kestrel-record`, its parser, and a HAR read into
      a list of recorded requests.
      Done when: a HAR exported by Chrome and one by mitmproxy both read to the
      same shape, an entry with no response reads as a request that got none,
      and the module is not on any other module's classpath.
- [x] **`spec-0080-emit`** — recorded requests to Kotlin source: one `exec` per
      request, headers, bodies, expected statuses.
      Done when: the generated file compiles, `./gradlew spotlessCheck` passes
      on it unmodified, and a recording of the cookbook's own checkout produces
      a scenario whose `stepNames` match the hand-written one.
      The first two are gates rather than claims: the generated file is checked
      in under `kestrel-record/src/test`, so this build compiles it, detekt
      reads it and spotless formats it, and a generator whose output was
      unformatted would move that file and fail the test that compares it. The
      names are read out of the source rather than off the generated value,
      because the value carries a `TODO` where its credential was — which is
      exactly what stops it running until somebody has decided what goes there.
- [x] **`spec-0080-redact`** — credentials dropped, and named where they were.
      Done when: `authorization`, `cookie`, `set-cookie`, `x-api-key` and any
      header whose value parses as a JWT are absent from the output, each
      leaving a `TODO` naming the header.
- [x] **`spec-0080-correlate`** — a value in one response that reappears in a
      later request becomes a `capture` and a `{name}`.
      Done when: a recording that creates an order and then fetches it by id
      generates the capture and the templated path, and a value that appears in
      two responses before it is used is not correlated to the wrong one.
- [x] **`spec-0080-collapse`** — runs of one path differing by a segment become
      one step.
      Done when: forty `GET /products/{n}` become one step named
      `GET /products/{id}` with a comment saying it stood for forty.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :kestrel-record:run --args="docs/examples/checkout.har --package example --out /tmp/out"
```

The generated file compiles, carries no credential, and runs.

## Open questions

1. **Where does the base URL come from?** A recording holds absolute URLs
    against one host. Recommend the most common origin as `http.baseUrl(...)`
    and everything else absolute, with a comment where more than one appeared.
    **Built that way.**
2. **How is a correlation proved rather than guessed?** A value seen in a
    response and a later request may be a coincidence — a status, a count, a
    date. Recommend a length floor and a shape test (opaque-looking, not a word
    in the request already), and generating a comment rather than a capture
    where it is uncertain.
    **Built that way**: eight characters, made of what an identifier is made of,
    carrying a digit, and not already in the request credited with producing it.
    Anything shorter is a comment. The producer is the *nearest* preceding
    answer carrying the value, which is what stops a value seen twice being
    credited to the wrong one.
3. **Should the recording keep think time?** The gaps are one person's, so no
    rate line should come from them. Recommend emitting them as a commented-out
    `pause` per step, which says what was seen without pretending it is a
    profile.
    **Built as a comment naming the gap** rather than as a commented-out
    `pause`: a commented-out call is a line somebody uncomments without reading,
    and what is worth keeping is the number and the sentence saying it is one
    person's.
4. **Is a `main` in a published module the right shape?** It makes
    `kestrel-record` the only module with an entry point. Recommend it, and
    excluding it from `publishedModules` if that reads wrong — a tool nobody
    depends on is not a library.
    **Published, with the entry point.** A second set of coordinates for one
    `main` is worse than one module that has one, and the module is on nobody
    else's classpath — which is a test, not a claim.
