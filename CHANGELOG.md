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
- **`kestrel-websocket`** — `open` and `close` as timed steps on
  `java.net.http.WebSocket`, one connection per user, held in the session.
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

### Changed

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

- **One arm per run.** `Simulation` holds a `List<Arm>` and core will build a
  two-arm simulation happily, but the engine still takes `arms.single()`, so
  running one throws `IllegalArgumentException` rather than sending both. Two
  journeys in one run is not available; two runs are.
- **No loops or conditionals in the DSL.** `Step.Repeat` and `Step.When` are in
  the model and the engine walks them, but `ScenarioBuilder` offers only `exec`,
  `pause` and `emit` — there is no `repeat`, `during` or `doIf`. Constructing
  `Scenario(name, steps)` and the `Step` values by hand works and is the only
  way in.
- **No closed model.** Every profile states departure times up front. There is
  no "hold 50 concurrent users", which is the shape a queueing model wants and
  the shape some teams' targets are specified in.
- **WebSockets connect and disconnect, and carry nothing.** `open` times the
  upgrade to the server's 101 and `close` times the Close frame out and back.
  Sending and receiving messages is not there, so a WebSocket load test measures
  handshakes.
- **No redirect following.** The HTTP client is built with
  `Redirect.NEVER` on purpose — a 302 the test did not expect is a finding, not
  a timing for a page nobody asked for — and there is no opt-in. A step that
  meets a redirect sees the 3xx and fails its expectation.
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
