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

## [Unreleased]

### Added

- **Scenarios as values.** `Scenario`, `Step`, `Action`, `Session` and
  `StepResult` in `kestrel-core`, with typed `SessionKey<T>` and a step body
  that names neither the session nor its result.
- **Open-model injection.** `ConstantRate` and `RampRate` state departure times
  up front; each offset is computed from its index so a long run cannot drift.
- **Measurement.** A log-linear `Histogram` written here rather than taken as a
  dependency, `RunResult`/`StepStats`/`Timing`, and two latencies per step so a
  generator's own backlog is never reported as the target's speed.
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
- **`kestrel-pelican`** — Pelican's `ClientTransport` over the JDK client, so a
  generated typed client runs inside a load test with no Pekko.
