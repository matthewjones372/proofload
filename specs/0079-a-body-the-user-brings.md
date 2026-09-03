# 0079 — A body the user brings

## Problem

`HttpAction.body(String)` takes one string, built when the scenario is built
and sent unchanged by every user (`HttpAction.kt:60`). A path is not like this:
`/orders/{id}` is filled from the session per user (`Capture.kt:39-52`), and
`{id}` in a *body* is sent to the target as the four characters `{id}`.

So a run where ten thousand users each place an order sends ten thousand
identical orders. Whatever the target does with a duplicate — dedupe it, serve
it from cache, collide on a unique index, take the idempotency key at its word —
it does ten thousand times, and the page reports that as the latency of placing
an order. The workaround is writing an `Action` by hand, at which point the
checks, captures, redirects, retries and trace headers are the caller's to
rebuild.

The second half is size. A body is a `String` all the way down to
`BodyPublishers.ofString` (`Transport.kt`), so a test that uploads anything
large holds it in memory, and one that uploads something larger than the heap
cannot run at all.

## Not doing

- **No templating language.** `{name}` from a session key of that name, the
  same idea paths already use — narrowed to identifiers, because a JSON
  document is braces all the way down — and no other. No expressions, no formatting, no
  escaping rules: a body is bytes the caller composed.
- **No JSON.** Nothing here knows the body is JSON, so nothing quotes or
  escapes for it. A value with a `"` in it is the caller's problem, as it is in
  a path with a `/` in it.
- **No body on the response side.** Reading a large response is 0005's
  `ofString` and stays so; this is the request half.
- **No repeat of a streamed body's content.** A stream is read once. A retry
  or a redirect asks for a fresh one, or it is not sent.

## Shape

```kotlin
// Filled per user from the session, exactly as `/orders/{id}` is.
api.post("/orders").body("""{"cart":"{cart}","key":"{idempotencyKey}"}""")

// Or streamed, one fresh stream per attempt.
api.put("/uploads/{id}").bodyFrom(bytes = null) { Files.newInputStream(archive) }
```

- `body(String)` gains the `{name}` rule. A body with no `{` costs nothing: the
  same early return the path fill already makes.
- A `{name}` the session has nothing under fails the step with `UnfilledPath`,
  as a path does — the failure is the same one, and it names the key.
- `bodyFrom(bytes) { stream }` sends without materialising. `bytes` is the
  content length where the caller knows it and null where they do not, in which
  case the request is chunked. The lambda is called once per attempt, so a
  retry and a redirect each get their own stream.
- `Request.body` becomes a sealed `Body`: `Text(String)`, `Streamed(bytes,
  open)`, or absent. A custom transport reads which it was handed.

## Why this shape

**The path's rule, not a new one.** A reader who has seen `/orders/{id}`
already knows what `{cart}` does, and `UnfilledPath` already says which key was
missing. A second syntax for the same job would be two things to learn and two
places for them to disagree. The one difference is what counts as a name: a
body is full of braces that are not placeholders, so only an identifier is one.

**A missing key fails rather than passing the braces on.** The alternative is
substituting the names the session has and leaving the rest alone, which sends
`{cart}` to the target and files the answer under this step. A typo would then
be a finding about a service that had no part in it.

**A supplier, not a stream.** `HttpRequest.BodyPublishers.ofInputStream` takes
a supplier for exactly this reason: the JDK may subscribe more than once, and
this module retries (0026) and follows redirects (0055) by re-sending the same
`Hop`. A `body(InputStream)` would work once and then send an empty body on
every attempt after it, which is a failure the report would file against the
target. The alternative — refusing retries and redirects on a streamed body —
is narrower and is what a caller hits second rather than first.

**A sealed `Body` on `Request`.** The seam carries a value, and a nullable
`String` cannot say "a stream this long". The break is a break: anything
implementing `Transport` reads `request.body` and must now match on it.
Recorded in the CHANGELOG, which is what pre-1.0 means here.

## Stack

- [x] **`spec-0079-templated`** — `{name}` in a body, filled from the session.
      Done when: a body carrying `{cart}` arrives at the target with the user's
      own value in it, a body with no braces is byte-identical to today, and a
      missing key fails the step with `UnfilledPath` naming it.
      **The path's own pattern could not be reused, found by reusing it.** A
      path rarely holds a brace and a body almost always does: under
      `\{([^{}]+)}` the body `{"cart":"1 anvil"}` is one placeholder named
      `"cart":"1 anvil"`, so every JSON body failed as a key the session had
      nothing under. The body rule is the same idea narrowed to identifiers —
      `\{([A-Za-z_][A-Za-z0-9_]*)}`, which is what a session key is called and
      what no JSON document opens with. The looser rule stays on paths, where
      narrowing it would be a break for nobody's benefit.
- [ ] **`spec-0079-streamed`** — `bodyFrom`, the sealed `Body`, and the
      transport that sends either.
      Done when: a body of a megabyte arrives whole without being held as a
      `String`, a retry sends the whole body a second time rather than an empty
      one, and a length given is sent as `content-length` while none is chunked.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Should a streamed body be templated too?** It cannot be: the substitution
    reads a string and a stream is not one. Recommend saying so where
    `bodyFrom` is written, rather than silently doing nothing.
2. **Does a templated body belong in the trace note?** `narrating` prints
    `> $body` (`HttpAction.kt:125`). Recommend printing the filled one — the
    trace exists to show what left — and printing a streamed body's length
    rather than its content.
3. **Should `{name}` be escapable?** A body that legitimately contains `{cart}`
    has no way to say so. Recommend leaving it until somebody hits it: a path
    has had the same hole since 0005 and nobody has.
