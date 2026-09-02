# Changelog

Notable changes, newest first, in the format of
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

Until 1.0, breaking changes come without a major bump and are recorded here.
From 1.0, the public API of the shipped modules is stable and a break waits for
a major release. What changed is recorded here rather than checked by a tool,
so a break that is not written down is a break nobody was told about.

A version is cut by tagging — `git tag v0.1.0 && git push --tags`. The build
reads the nearest `v` tag, so an untagged commit is a `-SNAPSHOT` of the next
one.

## [0.1.0] — unreleased

The first release. Ten modules, published together and versioned together.

Read the limitations before the features: what this does not do yet is short
enough to list, and long enough to matter.

### Added

- **Scenarios as values.** `Scenario`, `Step`, `Action`, `Session` and
  `StepResult` in `kestrel-core`, with typed `SessionKey<T>` and a step body
  that names neither the session nor its result. A scenario is a tree rather
  than a list: `Step.Repeat` and `Step.When` hold steps instead of naming one,
  so `name` sits on the leaves and `Scenario.stepNames` reads off every name a
  run can record.
- **Open-model injection.** `ConstantRate` and `RampRate` state departure times
  up front; each offset is computed from its index so a long run cannot drift.
  `then` puts them in `Stages` for a run that ramps, holds and ramps again, and
  `randomized(seed)` jitters the offsets of any of them from a seed the report
  records, so a rerun departs the same way.
- **Measurement.** A log-linear `Histogram` written here rather than taken as a
  dependency, `RunResult`/`StepStats`/`Timing`, and two latencies per step so a
  generator's own backlog is never reported as the target's speed —
  `Clock.ServiceTime` is what the target took, `Clock.ResponseTime` is measured
  from the departure the profile promised, and every goal names which it reads.
  `RunResult.timeline` is the same pair a second at a time, and the HTML report
  draws it.
- **Goals, judged rather than asserted.** `p50`/`p95`/`p99`/`p999`,
  `failureRate` and `goodput(step, under)` are values; a run answers each with a
  `Verdict` naming the goal, what was measured and what it was over by. A goal
  reads `ResponseTime` unless it is handed a `Clock`.
- **Steps that finish somewhere else.** `emit` records a step whose answer
  arrives out of band, keyed by a `Correlation`, and the run drains its
  `Completions` for a bounded window after the last departure. What is still
  outstanding is reported rather than dropped.
- **Comparing runs.** `RunResult.against(baseline)` answers a `Comparison`, and
  refuses to compare two runs whose shape differs rather than returning a number
  about nothing. `Runs.against` puts many runs on each side, so a `Difference`
  carries a `Spread` and can say it cannot tell instead of calling noise a
  regression.
- **`kestrel-baseline`** — a run written to a file and read back, and a
  directory of them read as `Runs`, so the comparison above has something to
  compare against between builds.
- **Progress says how long.** The line a run prints names the window the profile
  scheduled, and a capacity search names the bound it already computed. Neither
  is a forecast: both are read off values that exist before a request leaves.
- **`kestrel-engine`** — one virtual thread per user, departures started by a
  scheduler, recorders sharded rather than one per user, and a failed step
  abandoning that user rather than counting its later steps as successes.
- **The rate a scenario sustains.** `Scenario.sustainable(upTo, holding,
  expecting)` is a `Search`, and a value: it answers `rungs` and `worstCase`
  before a request leaves. Running one climbs a coarse ladder and bisects at
  the knee, and returns a `Capacity` — the highest rate every goal held at, the
  goal that stopped it, and the curve every rung is on. A rung the injector
  could not offer is void rather than failed, and ends the search.
  `Capacity.toHtmlReport()` draws the curve, with the operating point marked.
- **A credential that stays fresh.** `refreshing(every) { fetchToken() }` in
  `kestrel-core` fetches once before the run and again on a daemon scheduler,
  so a step reads `current` — a volatile read — rather than timing an identity
  provider. `Refreshing.fixed(value)` is the same value with no scheduler, and
  `stop()` ends the schedule.
- **`kestrel-http`** — steps on `java.net.http`, keyed on the path template.
  `withCookies()` carries cookies between a user's steps, in that user's own
  session rather than on the run's shared client, and `traced()` puts a W3C
  `traceparent` and a synthetic-traffic `baggage` entry on every request.
  Redirects are not followed.
- **One run at a time, across processes.** The in-JVM lock is now backed by a
  `FileLock` under `java.io.tmpdir`, so two JVMs on one host run one after the
  other rather than measuring each other, and a killed holder frees the machine
  with nothing to reap. It degrades rather than fails: a lock that cannot be
  taken says so once and the run goes ahead with the in-JVM guarantee.
  `kestrel.exclusive=false` opts out, `kestrel.exclusive.file` moves the lock,
  and `kestrel.exclusive.timeout` puts a ceiling on the wait. A run that queued
  says how long; one that did not says nothing.
- **A test can name its engine.** `Engine` is a `fun interface` in core; a
  JUnit class names one by implementing `RunsOn`, and a Kotest spec by calling
  `kestrel(engine)`. A class that names none runs on virtual threads, so bare
  `@LoadTest` is unchanged. A named engine is still held exclusively, so two
  tests never measure each other whichever engine sends them.
- **Loops and conditionals in the DSL.** `repeat(n) { }`, `during(window) { }`
  and `doIf(predicate) { }` in `ScenarioBuilder`, building the tree the engine
  already walked. `repeat` deliberately shadows `kotlin.repeat` inside a
  scenario — the stdlib one unrolls the body and gives the report a row per
  copy. A `during` loop reads its own clock between iterations, so no carrier is
  parked and a user still looping when the profile's window closes extends the
  run rather than being cut off.
- **`reached` on a step.** How many users got to a step, beside how many
  requests it made — so a loop reads as 30 requests from 10 users rather than as
  30 of something. A result with no user information behind it prints `—`
  rather than a zero nobody measured.
- **More than one journey in a run.** `Simulation(arms = listOf(…))` sends every
  arm, merged into one schedule by a lazy k-way merge of their own departure
  sequences rather than booked one after another — which would have handed the
  arrival recorder a gap running backwards. Each arm is fed from its own feeder
  at its own user number from zero, and a two-arm mix refuses a one-arm baseline
  naming the arm that is missing.
- **A ceiling measured over a socket.** `:benchmarks:ceiling` now sweeps the
  shipped HTTP step against a loopback target as well as a null step, and
  `docs/what-it-costs.md` leads with the figure and calls it a lower bound.
- **`kestrel-websocket`** — `open`, `send`, `awaiting` and `close` as timed
  steps on `java.net.http.WebSocket`, one connection per user, held in the
  session. `open` times the upgrade to the 101 alone and `close` the Close frame
  out and back; `send` is timed for the write and waits for nothing, `awaiting`
  for the wait alone. Answers pair with sends in departure order, and one that
  matches no send is counted as `unsolicited` rather than timed.
- **Redirects, followed in the step.** `following(max)` on an HTTP action walks
  the chain explicitly, one request per hop, with the shared client still on
  `Redirect.NEVER` — so a redirect nobody asked to follow stays a finding. 301,
  302 and 303 go on as a bodyless GET; 307 and 308 keep method and body; cookies
  carry across hops; `expecting()` judges where the chain lands; and a chain past
  `max` fails under `TooManyRedirects`.
- **A feeder straight from a CSV.** `csvFile.feeding(key, ...)` fills each key
  from the column of the same name, with a conversion overload for a key that is
  not a `String`. A key naming a column the file lacks fails when the feeder is
  built, not on user one.
- **The test frameworks are quiet.** `@LoadTest` and Kotest's `kestrel()` hand
  the runner `Progress.silent`, and a calibration no longer announces its
  twenty-four internal runs. A `main` still prints.
  `open` times the upgrade to the server's 101 and `close` times the Close
  frame out to the far end's Close back; neither is a message, and no message
  is timed yet.
- **`kestrel-junit5` and `kestrel-kotest`** — a load test in whichever
  framework is already there, with the runner handed over as a parameter.
- **`kestrel-report-html` and `kestrel-report-github`** — one self-contained
  interactive page, a markdown summary, `$GITHUB_STEP_SUMMARY`, and a Pages
  index.
- **`Scenario.trace(feeder)`** — one user walked through the ordinary step
  machinery and printed, a step to a line. It schedules nothing and returns
  nothing, so a diagnostic pass cannot be read as a measurement.
- **`kestrel-pelican`** — Pelican's `ClientTransport` over the JDK client, so a
  generated typed client runs inside a load test with no Pekko.

- **A warm-up the runner does.** `warmingUp(over)` on a run and on a search
  sends load before the measurement and records none of it: no histogram,
  second or lateness sample from a warm-up reaches the result, because it runs
  before the recorder exists. Each arm holds at the rate its own shape opens
  at, and a search warms every rung at that rung's rate — one warm-up at the
  start would leave the first rung, whose verdict decides whether the ladder
  climbs, measuring a cold JVM. The plan carries it, so the page says what was
  warmed and a comparison refuses to pool a warmed run with a cold one. There
  is no default: a tool that warms unless told otherwise changes what every
  run already written measures.
- **What a run that fell behind still measured.** `RunResult.offered` names the
  load asked for, the load that left and the window it took;
  `latePerSecond` and `heldScheduleFor` say *when* the schedule went rather
  than only that it did. The page, the job summary and a void rung now say the
  service times are the target at the load that left — service time is
  measured from the departure that happened, so a run the generator could not
  drive is a smaller experiment rather than a wasted one. Kestrel still will
  not throttle itself mid-run: a run that lowers its own rate measures a load
  it then does not report.
- **An exemplar beside a percentile.** A traced run keeps one trace id per
  histogram bucket — tens per step, not one per request — and
  `Timing.exemplar(percentile)` returns an id belonging to a request that
  landed in that bucket, never a neighbour's. The page prints it beside p99.9
  and carries it on the p99 cell. The id table is allocated on the first traced
  sample, so an untraced run pays nothing.
- **Attempts beside requests.** `StepStats.attempts` counts the round trips
  behind the requests, so a redirect followed is one request and two attempts
  rather than one request that quietly took two. A retry folded into one
  measurement would report the target as slower than it is and hide that it
  answered wrongly first.
- **A run you can watch counts what it recorded.** The progress line reports
  requests and failures as they land, from a volatile count per shard read
  approximately — it went in only because `:benchmarks:ceiling` reported the
  same ceiling with it as without.
- **One clock per run, owned by the recorder.** `RunRecorder` holds the run's
  monotonic origin and answers `sinceStart()`; `shard()` spawns recorders that
  share it. Every recording path asks the recorder rather than keeping an
  origin of its own, so a transport built before the run can no longer count
  seconds from its own construction.

### Changed

- **A step body can record more than one sample.** `Action.run` takes the
  `StepScope` the engine builds rather than a `Session`, and `StepScope.sample`
  reports an answer as the body observes it. A WebSocket `awaiting(count)` is
  now one sample per message, each measured from the send it answers, rather
  than one sample for the batch; a body that reports none is recorded exactly
  as before. Anything implementing `Action` by hand changes shape, and
  `run(session)` stays for callers that have a session and want the outcome.
- **The baseline format is version 5.** It carries the warm-up a run
  declared, because a comparison refuses to pool a warmed run with a cold one
  and a file that did not carry it would refuse every warmed run against every
  baseline ever written. Version 4 and version 3 files still read, and claim no
  warm-up.
- **A failed credential refresh no longer stops refreshing.** A fetch that
  threw out of `refreshing`'s scheduled task cancelled its own schedule, so one
  failure left every later refresh unscheduled and a soak reading a credential
  that expired an hour ago — arriving as the target answering 401. The failure
  is now caught, counted on `Refreshing.failures` and named in `lastFailure`,
  and the last good value stays in force.
- **`StepResult` carries what a step did as well as what it was.** `Ok` and
  `Failed` gained `attempts` and `trace`, both defaulted, and `StepScope`
  gained `attempted()` and `traced(id)` for a step body to report them.

- **A failure reason is a value, not a string.** `StepScope.fail` takes a
  `Reason` — `Said`, `Threw`, `TimedOut`, `Other`, or one a caller writes — and
  `Outcome.reasons` is keyed on it. Reported failures were strings until this
  week, so a step body that called `fail("...")` still compiles through `Said`,
  and anything that read a reason back as a `String` does not. A `Reason`
  implementation must be a data class or an object: one with identity equality
  gets a row per request instead of a count.

### Limitations

What this does not do yet. Each of these is checked against the tree at the
commit this section was written on, not planned or assumed.

- **A mix's departed share is a floor, not a count.** The report names the arm
  on every step row and prints the asked-for share beside the departed one, but
  nothing records a departure per arm: the departed share is derived from
  `reached`, the users counted at each step. That is exact for a scenario every
  user walks and a floor for one that opens with a condition, and the page says
  so. The progress line still names the first arm of a mix.
- **No closed model.** Every profile states departure times up front. There is
  no "hold 50 concurrent users", which is the shape a queueing model wants and
  the shape some teams' targets are specified in.
- **No Kafka, and no queue or database steps.** HTTP, WebSocket handshakes and
  Pelican endpoints are the protocols. `emit` is the seam for anything else, and
  the caller writes the client.
- **Read `behind` before any percentile.** Coordinated omission is handled
  rather than avoided: `ResponseTime` measures from the departure the profile
  promised, so a generator that fell behind reports it. But if `behind` is
  large, the injector did not offer the rate the run claims and every percentile
  in that report is about a smaller experiment than the one asked for.
- **The API is not frozen, and nothing records it.** The build has no binary
  compatibility check and the tree has no `.api` dump, so a break between 0.1.0
  and 0.2.0 is caught by this file and by nothing else. Until 1.0 a break comes
  without a major bump.
- **CI is not running.** GitHub Actions is blocked at the account level, so the
  `build` workflow has not run on this commit and nothing here is backed by a
  green tick. `./gradlew build` on a developer machine is what these modules
  have been checked with.
