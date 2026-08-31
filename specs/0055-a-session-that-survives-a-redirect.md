# 0055 — A session that survives a redirect

## Problem

`sharedClient` is built `followRedirects(NEVER)` and with no cookie handler.
Both defaults are right and the comment in `Transport.kt` says why: a 302
followed silently becomes a timing for a page nobody asked for, filed under the
old row's name.

The consequence is that a target with a login form cannot be tested. Signing in
sets a cookie and answers 302; Kestrel records a failure and drops the user.
The workaround is to capture `set-cookie` into a session key and put it back as
a header on every later request, which every project writes and which handles
one cookie and no expiry.

A cookie jar cannot come from the client here, and that is the crux. There is
one `HttpClient` for the whole run — deliberately, so TLS handshakes are not
measured — and `java.net.CookieHandler` is per client, so a jar on the client
would be one jar shared by every virtual user. That is worse than none: fifty
thousand users would take turns being one logged-in person.

## Not doing

- No RFC 6265. No domain matching, no path matching, no `Secure`, no
  `SameSite`. A load test sends to one base URL.
- No client per user. That measures connection setup, which is a different
  experiment.
- No automatic redirect following. It stays opt-in, per request.
- No form encoding, no HTML parsing, no login helper. Signing in is a step the
  caller writes.

## Shape

```kotlin
val api = http.baseUrl("https://shop.internal").withCookies()

val signedIn = scenario("signed in") {
    exec(signIn, api.post("/session").body(credentials).expecting(302).following())
    exec(browse, api.get("/account"))     // carries the cookie the 302 set
}
```

- `withCookies()` puts a per-user jar in the user's own session, under a key
  `kestrel-http` reserves. Applied on send, updated from `set-cookie`.
- `following(max = 1)` follows a redirect and records **each hop as its own
  attempt**, reusing the attempts-and-requests split 0026 introduces rather
  than inventing a second one.
- A chain that exceeds `max` fails the step, named for the limit it hit.

## Why this shape

The jar lives in the session because the session is already the per-user thing,
already immutable, and already passed to every step. Nothing new is
synchronised and the shared client stays shared.

Hops are attempts rather than one measurement. A sign-in that costs a 302 and
then a 200 is two round trips, and folding them into one sample reports a
service slower than either and hides that the second one is where the time
went. 0026 already needs `attempts` beside `count` for retries; a redirect is
the same shape and should not get a third counter.

`withCookies()` on the `Http` value rather than a global, for the reason `Http`
is a value at all: a test against two services should not have one of them
turning cookies on for the other.

This spec depends on 0026 landing first, for `attempts`. Building the counter
here would mean 0026 arrives to find it already there in a shape it did not
choose.

## Stack

- [ ] **`spec-0055-jar`** — the per-user jar, `withCookies()`, applied on send
      and updated from the response.
      Done when: a step that receives `set-cookie` sends it on the next step,
      two users never see each other's cookies, and a run without
      `withCookies()` sends no cookie header at all.
- [ ] **`spec-0055-following`** — `following(max)`, hops as attempts, and the
      failure when the chain runs long.
      Done when: a one-hop redirect reports one request and two attempts, and a
      chain past `max` fails under a name that says so.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Where does the reserved key live?** Recommend `kestrel-http`, so core
    never learns that HTTP exists. It means a session can carry a value core
    cannot name, which is already true of any caller's own key.
2. **Is expiry honoured?** Recommend not: a run shorter than a session cookie's
    life does not need it, and one longer than that is 0022's problem, which
    already handles the credential that has to stay fresh.
3. **Does a followed hop keep the step's name?** Recommend yes, one row, with
    the hop count on it — the alternative is a row per redirect target, which
    is the URL-keyed report 0005 rejected.
4. **Should `withCookies()` imply `following()`?** They travel together in
    practice. Recommend keeping them separate: a target that answers 302 and
    sets nothing, and one that sets a cookie on a 200, are both ordinary, and
    coupling them would mean one of those two cannot be expressed.
