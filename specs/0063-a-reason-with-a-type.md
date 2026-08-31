# 0063 — A reason with a type

## Problem

A failure's reason is a `String` from the moment it is written to the moment a
test reads it back:

```kotlin
result[placeOrder].failedWith("status 503")   // 41
```

`kestrel-http` already knows this is wrong. Beside the helper that builds that
string it says:

> A reason is a contract between the code that writes it and the test that
> reads it, and a literal on both sides is a contract nobody checks.

and then exports `fun status(code: Int): String`, which is the contract written
down and still not checked: the helper returns the same `String` the literal is,
so nothing stops the literal, and the README's own example uses it.

A string reason also cannot be asked anything. "How many of these were server
errors" is a `startsWith` over report text. "Which of these were the target
saying no, and which were the socket giving up" is a reader's eye.

## Not doing

- **No sealed hierarchy in core.** Reasons are produced by `kestrel-http`
  (a status, a named check, a capture that found nothing), by
  `kestrel-websocket` (no connection, a handshake that did not answer), and by
  any caller's own `fail` in a step this repository never sees. A sealed type
  cannot be extended outside its module, so core would have to know about HTTP
  to name a status — which is the layering this whole design exists to keep.
- **No message in an exception reason.** `Threw` carries the class and not the
  message, which is what `kestrel-http` already does and for the reason it
  gives: a message carries a host and a port, so it is a row per request.
- No change to the cardinality guard. `MAX_REASONS_PER_STEP` counts distinct
  reasons and a typed one is no less capable of being distinct per request.
- No change to the baseline format, which does not carry reasons at all.
- No removal of `fail(String)`. A step body that has no type to give is the
  case the escape hatch is for.

## Shape

Core declares what a reason is, and the three that belong to no protocol:

```kotlin
interface Reason {
    /** What a report prints, and what a reader recognises it by. */
    val described: String
}

data class Said(val text: String) : Reason        // what fail(String) records
data class Threw(val type: String) : Reason       // the class, never the message
data object TimedOut : Reason
data object Other : Reason                        // past the cardinality guard
```

`kestrel-http` declares its own, and `kestrel-websocket` likewise:

```kotlin
data class HttpStatus(val code: Int) : Reason
data class CheckFailed(val check: String) : Reason
data class NothingCaptured(val key: String) : Reason
```

so the contract is checked at both ends:

```kotlin
result[placeOrder].failedWith(HttpStatus(503))                       // 41
result[placeOrder].failed.reasons.keys
    .filterIsInstance<HttpStatus>()
    .filter { it.code >= 500 }                                       // ask it something
```

## Why this shape

An open interface rather than a sealed one because the set is genuinely open:
the module that knows what a failure means is the module that made the request,
and a caller's own step is as entitled to name one as this repository is. A
`when` over a reason therefore takes an `else`, which is honest — there is a
reason out there this file has not heard of — rather than the `else` over a
sealed type that `AGENTS.md` forbids, which is a case somebody forgot.

Equality is the grouping. A reason is a map key in `Outcome.reasons`, merged
across shards in `RunRecorder` and across runs in `Runs.merged`, so a reason
must be a value: a data class or an object. That is stated where the interface
is declared, and `fail(String)` wraps in `Said` so the escape hatch groups
correctly without a caller thinking about it.

It costs nothing on the measured path. A failure today allocates a `String`
from `"status $code"`; tomorrow it allocates a `HttpStatus`, which is smaller
and does no formatting — the text is built once, by a report, from `described`.

The alternative is `value class Reason(val text: String)` with typed
constructors. It closes the literal at the call site for a fraction of the work,
and it answers nothing: `filterIsInstance<HttpStatus>()` is the thing a string
in a wrapper still cannot do.

## Stack

- [x] **`spec-0063-reason`** — `Reason` in core, `Said`, `Threw`, `TimedOut`
      and `Other`; `StepScope.fail` taking either; `Outcome.reasons` and
      `failedWith` in terms of it; both reporters reading `described`.
      Done when: a step that failed twice for one reason counts two under one
      key; `fail("no customer fed")` reads back as `Said("no customer fed")`;
      and the cardinality guard still collapses the twenty-first reason into
      `Other`.
- [x] **`spec-0063-protocols`** — `HttpStatus`, `CheckFailed`,
      `NothingCaptured` and the unfilled-path reason in `kestrel-http`; the
      websocket module's two; `kestrel-pelican`'s status and throw.
      Done when: a 503 reads back as `HttpStatus(503)` rather than as text, a
      rejected check names itself, and `NoThirdPartyDependenciesTest` still
      passes for every module that gained a type.
- [x] **`spec-0063-asked`** — the README and the cookbook saying what a typed
      reason is for, rather than showing the literal this spec removes.
      Done when: no example in either file passes a string literal to
      `failedWith`.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Does `failedWith` keep a `String` overload?** It would read the reason's
    `described`, which is convenient and is the door this spec is closing.
    Recommend not, and letting `failedWith(Said("..."))` be how a caller reads
    back what they wrote — symmetric with `fail`, and typed at the assertion.
2. **Should `Reason` require being a data class?** The language cannot say so.
    Recommend saying it in the KDoc and relying on the fact that every reason
    this repository ships is one, since a caller who implements it with
    identity equality gets a row per request and will see it immediately.
3. **Is `Threw` core's or the transport's?** Something thrown on the path is
    not an HTTP fact, and the websocket module wants the same. Recommend core,
    with each transport deciding what it catches.
4. **What about `status(code)`?** It becomes `HttpStatus(code)` and the helper
    goes. Recommend removing it rather than leaving a function whose whole
    purpose was to stand in for the type this spec adds.
5. **What does `kestrel-pelican` name a status?** Settled in the building: its
    own `Status`, in its own package. It cannot depend on `kestrel-http` —
    `NoPekkoTest` asserts its runtime classpath is core and `pelican-core`, and
    `docs/modules.md` says so — and a run goes through one transport or the
    other, never both, so nothing is ever grouped across the two.
