# 0120 — More than one client

## Problem

`proofload-http` builds one `HttpClient` for the whole run (`Transport.kt:29`)
and every departure blocks in `send` on it (`Transport.kt:60`). The comment
there argues the right thing — a client per user would measure handshakes — but
one and one-per-user are not the only two numbers.

The JDK's client is not a thin wrapper over a socket. Each instance carries its
own selector thread and its own executor beneath it, so every request a run
makes passes through one thread's readiness loop however many cores the machine
has. That is a plausible per-injector bound; it is the one a generator built on
several event loops does not have; and it is the specific thing people mean when
they doubt this design scales. Nothing here has measured it.

0069 built the seam for exactly this — "a caller who needs more can hand in a
transport built on a client that goes faster" (`Transport.kt:36-43`). The
cheapest use of that seam is not a faster client. It is more of the same one.

## Not doing

- **No Netty, no second HTTP stack, no new dependency anywhere.** The question
  is whether one client is the bound, not whether another library is quicker.
- **No connection-pool flags and no executor swap.** One variable at a time,
  and this spec's variable is how many clients there are.
- **No change to `Transport`.** The seam takes a `Request` and answers an
  `Exchange`, and that is enough for this.
- **No change to the shipped default until a measurement says to**, and then
  only with the number that said so.
- **No public API in the first two entries.** The striping lives in
  `benchmarks` until there is a reason to publish it.

## Shape

A transport in the harness, and a column in the sweep:

```kotlin
// in benchmarks, over the seam 0069 already built
hitting(target).over(Striped(clients = 4))
```

```
over a socket — clients on the axis
rate     clients  behind p50  failed  files           per conn
25,000         1     3.10 ms   1,890   1,004 / 20,000     51.7
25,000         2     0.90 ms       0   2,010 / 20,000     50.9
25,000         4     0.31 ms       0   4,050 / 20,000     51.1
25,000         8     0.29 ms       0   8,100 / 20,000     50.4
```

A user lands on one client for its whole journey — `threadId() % clients`, the
striping `LoopbackTarget.kt:52` already uses for its tallies — so what changes
between rows is how many selectors the run has, not whether a connection is
reused. **Per conn** comes from 0118 and is in the table for that reason: K
clients hold K pools, so a row that improved because it opened more connections
must be visible as a row that improved for a different reason than the one
being tested.

## Why this shape

Measure before shipping, and publish a "no" as readily as a "yes". If the rows
are flat, the answer is that one client was never the bound — which is a result
worth having and costs a harness class rather than a public API somebody has to
live with. If they are not flat, the last entry ships it with the figure that
justified it.

The alternative is to ship `striped(n)` first and sweep it afterwards. Recommend
against: a published surface is a promise, `apiCheck` makes removing one a
diff that breaks somebody (0085), and this repository has no measurement saying
the promise is worth making.

## Stack

- [ ] **`spec-0120-striped`** — a striped `Transport` in `benchmarks`, and the
      clients axis on the over-a-socket sweep.
      Done when: the sweep runs at 1, 2, 4 and 8 clients, each row reporting
      lateness, failures, descriptors and requests per connection.
- [ ] **`spec-0120-record`** — `docs/what-it-costs.md` carrying what it found,
      including if what it found is nothing.
      Done when: the page says whether the number of clients moves the ceiling
      on this machine, and by how much.
- [ ] **`spec-0120-ship`** — `JdkHttpClient.striped(n)` in `proofload-http`,
      `apiDump`, CHANGELOG. **Only if the sweep says so.**
      Done when: a caller can write `over(JdkHttpClient.striped(4))`, the `.api`
      diff is in the same commit, and the KDoc names the measured figure and the
      machine it came from rather than recommending a number in the abstract.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :benchmarks:ceiling
```

## Open questions

1. **Striped by thread id, or a round-robin counter?** Recommend the thread id.
    A shared counter is an atomic increment on the departure path, which the
    measurement rules in AGENTS.md exist to keep off it, and the precedent is
    in `LoopbackTarget` already.
2. **Does this confound itself with connection count?** Yes, and that is why
    per-conn is a column. Recommend reporting both and drawing no conclusion
    from a row whose reuse figure also moved — 0118 has to land first for the
    column to exist, which makes it this spec's dependency rather than a
    nice-to-have.
3. **Which sweep carries the axis — the shared-cores one or 0118's?**
    Recommend 0118's. A selector thread contending with a target for the same
    cores is the confound this whole group of specs is removing.
4. **What if the rows are flat?** Recommend saying so on the page and closing
    the spec with `spec-0120-ship` unbuilt and a note under it, the way 0086
    records an entry it argued out of. A measured "no" is the outcome that
    settles this argument, and it is worth as much as the other one.
5. **Does a striped default change what a run measures?** It should not —
    redirects, cookies, traces, statuses and checks all stay above the seam by
    0069's design — but it changes how many connections a target sees from one
    injector. Recommend that `spec-0120-ship`, if it happens, leaves the default
    at one and documents the knob, rather than moving what every existing user's
    target experiences.
