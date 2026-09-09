# 0118 — A target that is not in the way

## Problem

`docs/what-it-costs.md` publishes **at least 2,500 a second** over a socket and
admits three times over that it cannot say whose limit that is: the target is
`com.sun.net.httpserver` in this JVM, on these cores, answering from memory
(`Ceiling.kt:64-77`, `LoopbackTarget.kt:78-88`). The page states the remedy and
does not take it — "separating the two needs a target that is not competing with
the generator for the machine".

The figure is now read as a capacity, and compared against numbers other tools
publish from injectors that had a machine to themselves. The sweep's own
attribution says it is not one: at ten thousand a second this process held 991
of 20,000 descriptors while sockets in TIME_WAIT reached 21,317 of a
28,232-port range. The wall the sweep found was the arrangement's. Nobody has
measured the shipped client with the target out of its way, so the honest answer
to "how fast is the injector" is still that this repository does not know.

## Not doing

- **No LAN, no second machine.** 0011 ruled it out and the reason has not moved.
  Another process on this host, on cores of its own, is the variable being
  removed — a network is a different one.
- **No comparison against Gatling, k6 or anything else.** 0011's rule stands. A
  larger number here is not an argument about a tool nobody measured.
- **No tuning of the client.** The shipped configuration exactly, per 0056's
  second open question. The question is what a user gets.
- **No replacement of either existing sweep.** A third table under the same
  rule, and the shared-cores row stays: it is what a CI runner looks like.
- **No new dependency in `benchmarks`.** Same server, moved.

## Shape

```bash
./gradlew :benchmarks:ceiling        # unchanged: the two tables of today
./gradlew :benchmarks:ceilingApart   # the third, target in its own JVM
```

```
over a socket, target apart — generator on 2 cores, target on 2
rate     requests  failed  behind p50  served p99  files          ports           per conn
10,000     50,000       0     0.09 ms     0.31 ms  1,004 / 20,000  1,120 / 28,232     48.2
25,000    125,000       0     0.22 ms     0.44 ms  1,190 / 20,000  2,980 / 28,232     51.7
```

The target keeps `LoopbackTarget` unchanged and gains a `main`: started by the
harness with `ProcessBuilder`, pinned where the platform can, writing its own
`served()` and its connection count to a file as it stops. **Per conn** is
requests divided by distinct client ports the target saw, so a port peak is
attributed to connection churn rather than guessed at.

## Why this shape

The confound is three variables — shared cores, shared heap and JIT, a server
nobody would ship — and they come off one at a time. A separate pinned JVM
removes two of them for a `ProcessBuilder` and a file, and leaves the third
falsifiable: if `served p99` stays flat as the rate climbs, the server was never
the bound and a faster one buys nothing; if it climbs, the number is still not
the client's and the next spec is a real server carrying its own dependency.

Going straight to that server, on other hardware, is the alternative. Recommend
against for now: it measures a network, it needs a machine nobody committed
here, and it is the shape of thing 0086 declined for the same reason.

## Stack

- [ ] **`spec-0118-apart`** — `LoopbackTarget` with a `main`, started and
      stopped by the harness, reporting its served timing back through a file.
      Done when: a sweep run against the out-of-process target produces the same
      columns as today's, and the target's own `served()` is in them.
- [ ] **`spec-0118-cores`** — disjoint CPU sets for generator and target, named
      in the table, degrading to "not pinned" where the platform has no way.
      Done when: a pinned row says which cores each end had, and an unpinnable
      platform still produces the table and says it did not pin.
- [ ] **`spec-0118-reuse`** — requests per connection, counted at the target as
      requests over distinct client ports.
      Done when: a row whose ports peak near the range shows a low figure here,
      and one that reused its connections shows a high one.
- [ ] **`spec-0118-record`** — `docs/what-it-costs.md` carrying the third table.
      Done when: the paragraph that "cannot say whether the client or the target
      ran out first" either does, or names exactly what is still missing.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :benchmarks:ceiling         # unchanged, per 0057's rule
./gradlew :benchmarks:ceilingApart
```

## Open questions

1. **How are the cores split, and does it matter that it is a choice?**
    Recommend half each and the split printed in the table's title. A generator
    given three of four cores is a different experiment from a user's laptop,
    and the existing table is the one that keeps the laptop honest.
2. **What if the platform cannot pin?** Recommend recording "not pinned" and
    running anyway, on `Headroom.Absent`'s precedent: a separate JVM alone
    removes the shared heap and is worth publishing without the pinning.
3. **Does the sweep reach further than `SOCKET_RATES` stops today?**
    Recommend extending to 25,000 and 50,000 only in this table. If the
    arrangement was the wall, those rows are the point of the spec; if they
    fail the same way, that is the finding and the ceiling does not move.
4. **Does `ceilingApart` run in CI?** Recommend not, per 0056's fourth question.
    It needs a quiet machine more than the existing sweep does, not less.
5. **Is counting distinct client ports cheap enough at fifty thousand a
    second?** It is a set insert per request on the target's own threads.
    Recommend striping it the way `LoopbackTarget` already stripes its
    histograms, and reporting the figure as absent above the rate where the
    count itself starts moving the served time.
