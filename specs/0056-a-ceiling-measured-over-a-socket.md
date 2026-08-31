# 0056 — A ceiling measured over a socket

## Problem

`docs/what-it-costs.md` publishes a ceiling of a hundred thousand a second, and
0011 was explicit about what it excluded: "the target is an action that returns
immediately without touching a socket". That was the right question for 0011 —
what do the scheduler and the recorders cost — and it is answered.

It is not the number a user is bounded by. Nobody runs null actions. They run
`kestrel-http`, which is a blocking `send` per virtual thread through one
shared `HttpClient` that has its own selector and its own executor between the
thread and the socket. Nothing in this repository has ever measured that path,
so the published figure is an upper bound on a path nobody takes, sitting under
a heading that reads like a capacity statement.

A tool whose thesis is that a generator must state its own ceiling is currently
stating the wrong one.

## Not doing

- No LAN. 0011 already ruled it out and the reason has not changed: a ceiling
  measured across a network measures the network.
- No comparison against Gatling, k6 or anything else. 0011's rule stands.
- No tuning of `HttpClient` — no executor swap, no connection-pool flags. This
  spec finds the number the shipped client reaches.
- No replacement of the null-action sweep. Two numbers, both kept.

## Shape

A target in the same process, answering immediately, so what is added over
0011's null action is exactly the client and the loopback stack:

```bash
./gradlew :benchmarks:ceiling      # both sweeps, one table each
```

```
over a socket
rate     requests   behind p50   behind p99   kept schedule
1,000       5,000      0.21 ms      1.10 ms    yes
10,000     50,000      0.90 ms      8.40 ms    yes
25,000    125,000     14.00 ms    210.00 ms    no
```

- `com.sun.net.httpserver` as the target: it is in the JDK, so `benchmarks`
  gains no dependency and the module's exclusions are unchanged.
- The same rule for "kept" as 0011 — `fellBehind()` is false.
- `docs/what-it-costs.md` carries both ceilings, the HTTP one first, each named
  for what it excludes.

## Why this shape

The honest hard part is that the loopback server may itself be the ceiling.
`com.sun.net.httpserver` is not a fast server, and a sweep that saturates it
would publish the server's capacity under the client's name — which is exactly
the failure this spec exists to fix, one layer down.

So the number is reported as a lower bound and said to be one: the client
reaches *at least* this rate. That is cheap, it is true, and it is more useful
than the current figure, which is an upper bound presented without one. Making
it an equality would mean characterising the server too, and that is a second
project measuring something nobody ships.

Generator and target sharing cores is not a flaw to correct. It is what a
laptop run and a single CI runner both look like, and the page says so.

## Stack

- [x] **`spec-0056-target`** — the loopback target, its lifecycle, and a check
      that it answers within a fixed budget at the rates swept.
      Done when: the target starts and stops with the harness, and the harness
      records the target's own service time beside the generator's lateness.
- [x] **`spec-0056-sweep`** — the over-the-socket sweep and its table.
      Done when: `:benchmarks:ceiling` writes both tables and picks a ceiling
      for each.
- [x] **`spec-0056-record`** — `docs/what-it-costs.md` carrying both, with the
      bound stated.
      Done when: the page leads with the HTTP ceiling, says it is a lower
      bound, and says what each sweep excluded.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build   # unchanged: benchmarks are not in this
./gradlew :benchmarks:ceiling
```

## Open questions

1. **How is "the server was not the bottleneck" evidenced?** Recommend
    recording the target's own handler time and reporting it in the table: a
    handler that stayed flat while lateness climbed says the client gave up
    first, and one that climbed with it says the server did.
2. **One connection per user, or the shared client's pool?** Recommend the
    shipped configuration exactly — the pooled shared client — because the
    point is to measure what users get, not what is achievable.
3. **Does this ceiling go in the README?** Recommend the README keeps no
    number at all and links the page, so there is one place to change when the
    machine does.
4. **Does the sweep run in CI?** Recommend not. It is a benchmark, it needs a
    quiet machine, and `AGENTS.md` already keeps benchmarks out of the build.
