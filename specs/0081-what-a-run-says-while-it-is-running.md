# 0081 — What a run says while it is running

## Problem

Every report is a function of a finished `RunResult`: `writeHtmlReport`,
`markdown`, `sendOtlp` (`Otlp.kt:59`), the exports, the baseline. So a
two-hour soak test says nothing for two hours. If it is going to fail, it fails
silently for two hours and then says so.

What exists is a progress line. `Progress.tick(elapsed, snapshot)`
(`Progress.kt:57`) is called on a thread of its own every sample interval
(`Watching.kt:105-113`) and carries a live `Snapshot` — departed, in flight,
`behind`, requests, failed (`Progress.kt:20-37`). `Progress.lines()` prints it.
Nothing else consumes it, so a team with Grafana already open watches a
terminal instead, and a run that lost its schedule at minute four is a thing
they find at minute one hundred and twenty.

The seam is right and it is one method. What is missing is anything on the
other end of it.

## Not doing

- **No new sampling.** The ticker already runs, already allocates one
  `Snapshot` on a thread no departure touches, and already keeps its counts by
  volatile store per shard rather than by a lock on the timed path
  (`Progress.kt:29-35`). Nothing here goes near that.
- **No per-step live numbers.** A `Snapshot` is the run, not the steps. Making
  it per step means reading the recorders while they are being written, which
  is the lock this design exists to avoid.
- **No live percentiles.** Same reason: a percentile needs the histograms, and
  those are merged when the run ends. Counts and the backlog are what is
  already published.
- **No server, no socket, no web page.** The report is a file (0021) and stays
  one. This pushes to something the team already runs.
- **No change to `Progress`.** It is a `fun interface` with defaults and every
  implementation here is one; a second method would break every caller's
  lambda.

## Shape

```kotlin
// Push the live snapshot to a collector, and the finished run as before.
checkout.at(50.perSecond, over = 2.hours)
    .run(Progress.lines() and otlpEvery(30.seconds, to = "http://collector:4317"))
```

```kotlin
/** Two reporters, both told. */
infix fun Progress.and(other: Progress): Progress
```

- `and` composes: the terminal keeps its line, the collector gets its metric,
  and neither knows about the other.
- `otlpEvery(interval, to)` in `proofload-otel`, publishing what a `Snapshot`
  holds as gauges and counters under the same names `sendOtlp` already uses for
  the finished run, so one dashboard reads both.
- `Progress.throttled(every)` in core, so a reporter that costs something is
  called at its own interval rather than at the sampler's.
- Nothing is added to `Progress`. `and` and `throttled` are ordinary functions
  over the interface that exists.

## Why this shape

**Compose, do not extend.** The alternative is a list of reporters on the
runner, which is a second place to configure the same thing and a `Progress`
that means something different depending on where it was passed. `and` is four
lines and reads at the call site.

**Counters and a backlog, not percentiles.** What a watcher needs at minute
four is *is it still keeping up* — which is `behind`, published already — and
*is anything failing*, which is the failure count. A live p99 would need the
histograms under the writers, and this whole design keeps readers off them. A
run's percentiles arrive when the run does, and that is the honest boundary.

**The same metric names as the finished run.** `sendOtlp` already names its
series (`Otlp.kt:90-168`). A live push under different names would be two
dashboards for one run, and the join between them would be somebody's
convention.

**Throttling belongs to the reporter.** The sampler ticks faster than any line
is due, deliberately (`Watching.kt:92-95`). A reporter that posts over a
network wants its own interval, and asking the ticker to slow down would slow
the terminal line with it.

## Stack

- [x] **`spec-0081-compose`** — `and` and `throttled` in core.
      Done when: two reporters both see every tick, a throttled one sees at
      most one per interval and always sees the last tick of the run, and
      `Progress.silent and Progress.silent` prints nothing.
- [x] **`spec-0081-otlp`** — `otlpEvery` in `proofload-otel`.
      Done when: a run pushes departed, in-flight, requests, failed and
      `behind` under the names the finished run uses; a collector that refuses
      is a warning rather than a failed run; and nothing is pushed after the
      last tick.
      Built with one deviation, argued rather than silent: the lateness is
      `proofload.behind.last` rather than `proofload.behind`. The finished export's
      `proofload.behind` is a histogram over every departure and a live tick has
      one sample, and a collector handed the same name as a histogram and as a
      gauge drops whichever it saw second. `requests` and `failures` keep their
      names, carry no `step` — a snapshot is the run rather than its steps — and
      are therefore their own series rather than points added to the finished
      export's.
- [x] **`spec-0081-docs`** — the cookbook recipe.
      Done when: the page says what is live and what is not, and why a
      percentile is not.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

A two-hour run shows its backlog and failure count in a collector from the
first tick.

## Open questions

1. **Must a reporter's failure fail the run?** A collector that is down would
    otherwise end a two-hour test at minute one. Recommend not: catch, warn
    once, and keep running — losing the dashboard is not losing the
    measurement.
2. **Where does a reporter's own cost land?** It runs on the sampler thread, so
    a slow push delays the next tick rather than a departure. Recommend saying
    so, and recommending `throttled` for anything over a network.
3. **Should `and` be `plus`?** `a + b` reads well for a list of reporters.
    Recommend `and`, because `+` on two things that both do the whole job reads
    as merging rather than as tee-ing.
