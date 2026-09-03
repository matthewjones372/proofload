# 0082 — A response nobody has to hold

## Problem

Every response is read into a `String` before anything looks at it:
`BodyHandlers.ofString()` (`Transport.kt:48`), kept on `Response.body`
(`Response.kt:9`). So a step that downloads a 200 MB export holds 200 MB per
user, and a run of fifty concurrent users needs ten gigabytes to measure a
download that the target streams.

0079 fixed the request half — `bodyFrom` sends without materialising — and left
this one, which is the half most load tests actually hit: a service under test
returns more than it accepts.

The reason it is held is real rather than an oversight. A `check` reads the
body (`Check.kt:9-12`), a `capture` reads it (`HttpAction.kt`), the trace prints
it, and a redirect walk needs the status but not the bytes. Those are the
features that make the body worth having. What is missing is the case where
nobody wants the bytes at all and the measurement is how long they took to
arrive.

## Not doing

- **No change to a step that checks or captures.** Those read the body, so
  those hold it. This adds a way to say that nothing will.
- **No streaming to the caller.** A body handed out as an `InputStream` is one
  the user's thread must drain before the step ends, or the connection leaks
  and the sample is a lie. This drains it and counts it.
- **No partial reads.** "First N bytes then hang up" measures a target's
  behaviour under an abandoning client, which is a different experiment and one
  a caller can build with their own transport.
- **No response-side templating, checks on a hash, or content assertions.**
  Nothing that would need the bytes back.

## Shape

```kotlin
// The measurement is how long the bytes took, and the bytes are not kept.
exec(download, api.get("/exports/{id}").discardingBody())
```

```kotlin
/** How many bytes came back, where the step did not keep them. */
val Response.bytes: Long
```

- `discardingBody()` on `HttpAction` sends the same request and drains the
  response through `BodyHandlers.ofByteArrayConsumer`, counting and discarding.
  `Response.body` is empty and `Response.bytes` is the count.
- A `check` or a `capture` on a discarding step is refused **where it is
  written**, not at run time: both read a body that will not exist, and a step
  that silently checks an empty string is a green test about nothing.
- `Body` gained a shape in 0079 for the request side; the response side gets
  the same treatment on the seam: `Exchange.Answered` carries a `Response`
  whose bytes may be counted rather than kept, and a transport says which it
  did.

## Why this shape

**Opt in, not out.** Defaulting to discarding would break every check and
capture in every existing scenario, and the failure would be a check that
passes against an empty body. Asking for it is one call on the steps that want
it, and the steps that want it are the ones somebody wrote deliberately.

**Refused where it is written.** `discardingBody().check(...)` is a mistake a
compiler or a builder can catch, and catching it at run time makes it a failed
step in a report instead of a line somebody deletes. This repository refuses a
unary descriptor to `stream` (0071) and a `KeptSchedule` goal on a closed run
(0024) for exactly this reason.

**Counted, because a byte count is a finding.** A download that returned 4 KB
instead of 200 MB took no time at all and looks like a fast target. `bytes` is
what tells the difference, and it is free — the consumer already sees every
buffer.

**A transport must be able to say it did neither.** The seam is public, so a
transport built on another client may not support discarding. It answers with
the body it read, the step gets its bytes from the string's length, and nothing
lies; the memory saving is the JDK transport's and any transport that
implements it.

## Stack

- [ ] **`spec-0082-discard`** — `discardingBody`, the counting handler, and
      `Response.bytes`.
      Done when: a 200 MB response is measured with a heap too small to hold
      it, `bytes` is the length the target sent, and `body` is empty.
- [ ] **`spec-0082-refuse`** — a check or capture on a discarding step refused
      where it is written.
      Done when: `discardingBody().check(...)` and `.capture(...)` both throw
      at build time naming which, and the message says to drop one or the
      other.
- [ ] **`spec-0082-docs`** — the cookbook recipe and the limitation removed
      from the CHANGELOG.
      Done when: the page says what is not available on a discarded response.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

A download test runs in a heap smaller than one response.

## Open questions

1. **Should `bytes` be on every response?** A held body already knows its
    length, so the field could always be right. Recommend yes — one accessor
    that is always true beats one that is only true sometimes.
2. **Does a discarded body break the redirect walk?** A hop reads `Location`
    from the headers, not the body, so it should not. Recommend a test that
    says so rather than a comment claiming it.
3. **Should there be a size ceiling instead?** "Hold up to 1 MB, discard the
    rest" would keep checks working on large responses. Recommend not: a check
    against a truncated body is the silently-green failure this spec is
    avoiding, spelled differently.
