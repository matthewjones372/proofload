# 0070 — More than one injector

## Problem

0004 wrote the boundary down: "No distributed run. One JVM." It is now the
largest limit in the tool. `docs/what-it-costs.md` measures the ceiling over a
socket at **at least 2,500 a second** on four loaded cores, so a team needing
20,000 gets a climbing `behind`, a void rung and no answer about the target
`[INJECTOR]`. Four JVMs by hand is four plans nobody proved alike, four
percentile sets that cannot honestly be combined `[PERCENTILES]`, and four
timelines whose second 30 is not the same second 30. Gatling, k6 and Locust all
do this; Hyperfoil was built for it `[HYPERFOIL]`. This supersedes 0004's
boundary, changing nothing inside it.

## Not doing

- **No control plane.** No agent, daemon, registry or RPC; no UI.
- **No provisioning, containers or orchestration.** Always the user's problem.
- **No shared wall clock.** Nothing on the sample path reads another host's.
- **Nothing the coordinator does during a run that a departure waits on.**
- **No averaging percentiles across injectors**, ever. 0037 settled that.

## Shape

```kotlin
kestrel.run(soak.sharded(index = 2, of = 4, startingAt = at))   // on each host
Shards.readAll(Path.of("build/kestrel")).merged[pay].responseTime.p99
```

**Partition.** Injector *k* of *N* sends the departures whose user number is
`≡ k (mod N)`, filtered per arm, so every shard sends the same mix. `Shard(index,
of, startingAt)` sits on `Simulation`, not `Engine` — 0051 kept that seam to one
method. Nothing coordinates: 0004 computes each offset from its index, so the
union over all *k* is every user once and **four injectors offer exactly the
departures one JVM would**. Feeders (0015) and seeded arrivals (0034) follow,
being functions of that number too.

**Clocks.** A sample is two readings of the injector's own `System.nanoTime()`,
honest with no shared clock. Only the *start* aligns, because the merge
superimposes timelines (0046) and a late shard smears second *n* and 0032's
settling point. The coordinator names an `Instant`; each waits on its own wall
clock. 0025 fixed the resolution at one second, so 100 ms is a tenth of a bucket
and NTP beats it: sub-second alignment buys the picture, not the honesty. An
injector whose instant has passed writes no file.

**Aggregation.** Each injector `writeInto`s a directory; the coordinator merges
by 0037's arithmetic — buckets added, percentiles off the sum. `Shards` is its
own type, not a flag on `Runs`. A shard carries the **whole** plan unmodified,
so `Plan.unlike` passes by construction and its share is derived —
`plannedUsers / of`, interval `plannedInterval * of`. `behind` and `hiccups`
come from the **worst** shard, not the pool: shard *k*'s departures are *N*
intervals apart, so pooled lateness against the whole run's interval calls a
rung void when every injector kept its schedule, and one shard losing ground
voids the run. An incomplete set is refused — exactly `0..N-1` of one *N*, both
numbers on the shard line. Counts stay **summed**, unlike machines stay refused
(0039's floor is per machine), and `Probe`s are compared.

**Exclusivity and transport.** 0050's lock stays per host and the coordinator
holds nothing. New rule: take the host lock **before** waiting for the instant,
since `Exclusivity.Waiting` inside the alignment window de-aligns the timeline.
The plan cannot be transported — a `Scenario` is Kotlin — so each injector runs
the same jar and gets three scalars, and the file's plan lines prove they ran
the same thing. Version 4 writes no lateness at all (`parseBaseline` returns an
empty `behind`), so **version 5 adds `behind` and `hiccups` as buckets and a
`shard` line**, with the shard in the filename. `Runs.readAll` refuses a shard
file: handed to 0038 it gives an interval over four quarters of one run and
calls it process variance. *N* shards make a `RunResult`; *M* make a `Runs`.

## Why this shape

The partition is a filter on an index and everything follows. A scaled-down
profile per injector — `hold(2_500.perSecond)` four times — is easier and wrong
three ways: the arrival sequence is no longer the one a single JVM produces, so
a distributed run cannot be checked locally; a randomised profile becomes four
unrelated draws; and each shard's plan differs from the whole, forcing open the
unlike-plan refusal, the only thing between a user and four pooled experiments.

## Stack

- [ ] **`spec-0070-shard`** — `Shard`, `Simulation.sharded`, the per-arm filter.
      Done when: four shards depart exactly the departures one JVM departs, at
      the same offsets, each user sent by exactly one shard.
- [ ] **`spec-0070-start`** — waiting for the instant on the injector's own
      clock, after the host lock and before the run's clock.
      Done when: an injector handed a past instant writes nothing, two JVMs
      given one instant depart within 100 ms, an unsharded run is unchanged.
- [ ] **`spec-0070-file`** — version 5: `behind` and `hiccups` as buckets, the
      shard line, the shard in the filename.
      Done when: a run round-trips its `behind.p99`, a version 4 file still
      reads, and two shards aligned to one millisecond do not collide.
- [ ] **`spec-0070-shards`** — `Shards`: the merge, the derived share, the worst
      injector's lateness, the refusals.
      Done when: four shards merge to summed counts with a p99 off them; three
      of four are refused by index; an unlike machine is refused; one shard
      losing ground voids the run; `Runs.readAll` refuses a directory of them.
- [ ] **`spec-0070-recipe`** — `Shards.readAll`, and the docs page for ssh.
      Done when: four JVMs on one host, given one instant, read back as one
      `Shards` whose merged count is `userCount()`.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :examples:timingTests
```

## Open questions

1. **Worst shard's `behind`, or pooled with the verdict on `Shards`?** Recommend
    the worst, named: `merged` is a `RunResult`, so a pooled one dilutes it.
2. **What is `merged.arrivals`?** 0037's reason does not apply — shards
    interleave into the single-JVM sequence — but a shard's gaps are *N* times
    the run's. Recommend `none`: a CoV off the profile is nobody's measurement.
3. **The alignment budget, and who checks it?** Recommend 100 ms, from each
    file's `startedAt` afterwards and never during the run; shards further
    apart are reported, not refused, since only the timeline smears.
4. **Should unlike machines merge, carrying every machine?** Recommend no, per
    0037: a merged result has one `Machine`, one `Probe`, one resolution.
5. **Where does the coordinator live?** Recommend `kestrel-baseline` beside
    `readAll`; a `main` and a shell loop are the launcher, as 0037 concluded.
