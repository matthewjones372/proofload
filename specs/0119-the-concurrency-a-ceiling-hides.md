# 0119 — The concurrency a ceiling hides

## Problem

Both sweeps in `docs/what-it-costs.md` point at a target that answers
immediately: the null step returns without touching anything, and the socket
step reaches a handler that writes two bytes from memory in 17 µs
(`Ceiling.kt:74`, `LoopbackTarget.kt:52-56`). By Little's law — which 0068
already computes on every run — ten thousand a second against a 17 µs target is
fewer than one user in flight. Every published figure is a departure rate at
*no concurrency*.

That is not what anybody runs. Ten thousand a second against a target answering
in 200 ms is two thousand users in flight; fifty thousand is ten thousand of
them. Each is a virtual thread parked in `client.send` (`Transport.kt:60`), and
holding thousands of those is precisely the cost that a generator built on an
event loop does not pay. The repository has never measured it, so the design's
central bet is unevidenced in the one regime where it is contested.

`RunResult.usersInFlight` (`RunResult.kt:528`) already records the population a
second at a time. What is missing is a target slow enough to create one.

## Not doing

- **No new engine and no new transport.** This measures what ships. If a number
  here is bad, the spec that changes something comes after it.
- **No comparison against another tool.** 0011's rule stands.
- **No network.** A delay inside the handler is a service time, not a hop, and
  it is the variable this needs.
- **No replacement of either sweep.** A second axis on the socket one.
- **No per-row heap tuning.** `-Xmx2g` stays as `benchmarks/build.gradle.kts`
  sets it, and a row that exhausts it is a published finding rather than a row
  to re-run bigger.

## Shape

```bash
./gradlew :benchmarks:concurrency
```

```
rate     service   in flight   behind p50  behind p99  failed  files           heap   room
1,000     200 ms         200      0.14 ms      0.9 ms       0    412 / 20,000  380 MB   yes
10,000    200 ms       2,000      0.31 ms      4.1 ms       0  2,180 / 20,000  1.1 GB   yes
10,000       1 s      10,000      1.90 ms     41.0 ms   1,204 14,700 / 20,000  1.9 GB    no
```

The target takes a declared delay before answering and its own `served()` proves
it took it. **In flight** is the peak over the steady segment (0032), not the
whole run, so a ramp does not set it. **Room** is `ranOutOfRoom()` (0065): a row
that hit a descriptor or port ceiling says so rather than being left out of the
grid.

## Why this shape

Service time is the one variable that turns a rate into a concurrency, and both
existing sweeps hold it at zero. Adding it as an axis on the socket sweep keeps
one rule for "kept its schedule" across all three tables, so they can be read
against each other — which is the property 0056 was careful to preserve when it
added the second.

The alternative is inferring the cost: take the published rate, multiply by a
plausible target latency, and reason about what that many threads would weigh.
That is a guess wearing a measurement's clothes, which 0065 refused for the
failure counts and which this repository refuses generally.

Heap and RSS go in the row because the doubt is specifically about what a parked
user costs, and `Footprint.kt` already establishes how this repository measures
that.

## Stack

- [ ] **`spec-0119-slow-target`** — `LoopbackTarget` delaying by a declared
      duration before it answers.
      Done when: a target told to take 200 ms reports a `served()` p50 within a
      few milliseconds of it, and one told to take nothing is unchanged.
- [ ] **`spec-0119-sweep`** — the rate × service-time grid, with in-flight peak
      and Little's law per row.
      Done when: each row's in-flight peak agrees with rate × service time to
      within the tolerance 0068 already uses, or the row says why it does not.
- [ ] **`spec-0119-cost`** — heap and RSS per row, on `Footprint`'s method.
      Done when: a row holding ten thousand users in flight names what the
      process was holding while it did.
- [ ] **`spec-0119-record`** — `docs/what-it-costs.md` carrying the grid, and
      saying plainly that the other two tables are the zero-concurrency case.
      Done when: a reader can find what this tool costs at the concurrency their
      own target would produce, or read that the grid stops short of it.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :benchmarks:ceiling         # unchanged, per 0057's rule
./gradlew :benchmarks:concurrency
```

## Open questions

1. **How does the target wait?** A handler that sleeps is a `Thread.sleep`, and
    detekt forbids one outside tests — for the reason in AGENTS.md, that a
    parked thread turns into latency somebody measures. Here that is the whole
    point: it is the *target's* latency, and it is meant to be measured.
    Recommend the suppression with that as its stated reason, over scheduling
    the completion, which is a second implementation of a delay in the one
    place a plain one is honest.
2. **Which service times?** Recommend 1 ms, 50 ms, 200 ms and 1 s — a cache, an
    ordinary API, a slow one, and one close to a timeout. Four times the socket
    rates is a long sweep; recommend cutting the rate list rather than the
    service times, since rate is the axis already covered elsewhere.
3. **Descriptors will run out.** Ten thousand in flight over HTTP/1.1 is ten
    thousand connections against a 20,000 limit. Recommend running those rows
    anyway and reporting `ranOutOfRoom()`, rather than trimming the grid to
    what fits: a limit found is the measurement, and a grid trimmed to avoid it
    publishes the flattering half.
4. **Peak in flight, or the steady segment's?** Recommend the steady segment,
    per 0032. A peak taken across a ramp is a number about the ramp.
5. **Does this belong in the same `:benchmarks:ceiling` task?** Recommend its
    own, because the grid is much longer than the two sweeps and 0057's rule
    says the existing ceiling must not move.
