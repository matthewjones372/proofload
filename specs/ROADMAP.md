# Where this is up to

Not a spec: an index, so nobody has to open twenty files to find out what is
built. Each row links the spec that argued for it.

## Built

| Spec | What it got |
|---|---|
| [0001](0001-scenarios-as-values.md) | scenarios, steps, sessions, actions — as values |
| [0002](0002-a-dsl-worth-using.md) | typed session keys, a step body with no ceremony, `at` |
| [0003](0003-what-a-run-measured.md) | histogram, `RunResult`, two timings per step |
| [0004](0004-the-engine.md) | virtual threads, departures on a schedule, sharded recorders |
| [0005](0005-http.md) | HTTP steps on `java.net.http`, keyed by path template |
| [0006](0006-a-report-you-can-open.md) | one self-contained interactive HTML file |
| [0007](0007-numbers-in-the-pipeline.md) | markdown, job summary, a Pages index |
| [0008](0008-a-load-test-is-a-test.md) | `@LoadTest`, the runner as a parameter |
| [0009](0009-kotest.md) | the same, in a Kotest spec |
| [0010](0010-pelican.md) | Pelican endpoints as steps, no Pekko |
| [0011](0011-what-this-tool-costs.md) | the ceiling harness — and the drift bug it found |
| [0012](0012-steps-you-can-point-at.md) | step handles instead of strings |
| [0013](0013-http-steps-that-read-like-steps.md) | `exec(browse, api.get(...))`, `send` taking the request |
| [0014](0014-a-load-shape.md) | stages: ramp, hold, ramp down |
| [0015](0015-every-user-is-different.md) | feeders, as a function of the user's number |
| [0016](0016-assertions-that-read.md) | `failedWith`, `ran`, `status(503)` |
| [0017](0017-graphs-drawn-from-buckets.md) | distributions drawn from the buckets |
| [0018](0018-a-report-that-explains-itself.md) | sample weights, journey risk, no averages |
| [0019](0019-what-was-asked-for.md) | the plan, the shape, and what never went out |
| [0020](0020-what-good-looks-like.md) | goals, verdicts, and the margin they missed by |
| [0021](0021-worse-than-last-time.md) | sampling intervals and baseline comparison |
| [0035](0035-a-tail-you-can-see.md) | `p999`, any percentile off the frozen buckets |
| [0034](0034-arrivals-that-are-not-a-metronome.md) | Poisson arrivals from a seed, and the CoV they produced |
| [0041](0041-a-clock-test-that-really-runs-alone.md) | the wall-clock tests out of `build`, and a gate that keeps them out |
| [0031](0031-the-rate-it-sustains.md) | `sustainable`, the ladder-then-bisect search, and the curve |
| [0033](0033-the-share-that-met-the-target.md) | `share`, `goodput`, and a goal that reads like an SLI |
| [0040](0040-an-answer-that-arrives-somewhere-else.md) | `emit`, `completing`, and the records that never arrived |
| [0025](0025-what-happened-when.md) | a coarse histogram, a per-second timeline, and the charts over it |
| [0037](0037-more-than-one-run.md) | `Runs`, merged from buckets, refusing unlike plans and machines |
| [0036](0036-a-slow-error-is-not-a-fast-one.md) | `Outcome`: what the failures took, apart from what the successes did |
| [0042](0042-a-test-that-does-not-measure-the-neighbours.md) | the repo's own timing test judged against the machine it runs on |
| [0046](0046-a-merge-that-drops-the-clock.md) | a merged run keeps its clock, second n against second n |
| [0032](0032-steady-state-found-rather-than-assumed.md) | `SteadyState`, `result.steady`, and goals judged over the segment |
| [0038](0038-better-worse-or-cannot-tell.md) | `Difference`: a bootstrap interval, and a verdict that can say it cannot tell |
| [0043](0043-void-is-not-the-same-question-as-behind.md) | a rung judged on the schedule it kept, not on the target's slowness |
| [0039](0039-what-this-machine-can-resolve.md) | hiccups, the floor, and a comparison that consults it |
| [0022](0022-a-token-that-stays-fresh.md) | `refreshing`, a credential fetched off the measured path |
| [0023](0023-a-number-you-can-follow.md) | `traced()`: a `traceparent` on every request, and an exemplar beside the percentile |
| [0024](0024-think-time-and-closed-model.md) | `pause`, a step that records nothing and is not latency, and `users(n)` looping — the closed model, labelled with what it cannot see |
| [0026](0026-checks-and-retries.md) | `checking`, a body check that fails under its own name, and `retrying` whose latency is the last attempt |
| [0027](0027-the-docs-a-newcomer-needs.md) | `docs/modules.md` with a test behind every row, and the cookbook |
| [0028](0028-a-run-that-does-not-book-a-million-tasks.md) | a booking window, so a run books departures a window at a time |
| [0029](0029-cutting-0-1-0.md) | `publishToMavenLocal` plus a smoke project that resolves the published coordinates, and the changelog |
| [0030](0030-a-baseline-in-ci.md) | `calibrate()`, measured once per JVM, and the CI recipe |
| [0048](0048-a-floor-measured-where-the-claim-is.md) | the absolute floor, read at the magnitude of the claim |
| [0049](0049-response-time-second-by-second.md) | response time on every `Second`, so the steady segment narrows both clocks |
| [0050](0050-one-run-at-a-time.md) | one run at a time — in the JVM, and across processes with a file lock that degrades rather than fails |
| [0051](0051-an-engine-core-declares.md) | `Engine` in core, `VirtualThreads` as one of them, `RunsOn` / `kestrel(engine)` to name another |
| [0052](0052-more-than-one-scenario.md) | a mix of arms on one merged schedule, named per row on the page |
| [0053](0053-control-flow-in-a-scenario.md) | `repeat`, `during`, `doIf`, and `reached` beside `count` |
| [0054](0054-a-feeder-that-reads-a-file.md) | `csv(path)` and `feeding(keys)`, a column per key, failing before the run |
| [0055](0055-a-session-that-survives-a-redirect.md) | a per-user cookie jar, and `following(max)` walked in the step |
| [0056](0056-a-ceiling-measured-over-a-socket.md) | the HTTP ceiling measured over a loopback socket, on the page as a lower bound |
| [0057](0057-a-run-you-can-watch.md) | `Progress`: a line every five seconds, silent under a test framework, a countdown and a bound |
| [0058](0058-what-was-actually-sent.md) | `trace`, one user walked with the URL, headers, body, status and captures printed |
| [0059](0059-an-answer-that-streams.md) | `kestrel-websocket`: `open`, `send`, `awaiting` a sample per message, `close`, a connection per user |
| [0062](0062-a-spread-measured-on-the-thing-compared.md) | `RegressionTest` judged over populations, and a page that says what a single-run interval does not bound |
| [0063](0063-a-reason-with-a-type.md) | `Reason`: a failure is a value, and the module that made the request names it |
| [0064](0064-how-long-this-will-take.md) | a run says its schedule and counts down; a search says its bound and narrows it |
| [0045](0045-a-clock-the-recorder-owns.md) | one monotonic origin per run, owned by the recorder every path records through |
| [0066](0066-a-warm-up-the-runner-does.md) | a warm-up declared on the run and on every rung, sent and recorded nowhere |
| [0076](0076-a-run-that-fell-behind-is-still-a-measurement.md) | the load that left, the second the schedule went, and what a void rung still measured |
| [0075](0075-a-step-that-records-more-than-one-sample.md) | the engine builds the scope a step reports through, and a body can record every answer it saw |
| [0065](0065-the-injectors-own-limits.md) | descriptors, ports and CPU sampled while a run measures, so a failure count says which end ran out |
| [0068](0068-littles-law-on-every-run.md) | L = λW checked every run, and an in-flight count that stopped measuring the booking window |
| [0067](0067-think-time-that-is-not-a-constant.md) | think time drawn from a distribution and a seed, so users stop clicking again in the same instant |
| [0069](0069-a-transport-the-http-module-can-swap.md) | a Transport seam under the HTTP step, with the JDK client as the default and a contract test for any other |
| [0072](0072-replaying-real-arrivals.md) | a captured hour replayed as the arrival process it was, scaled by time so its burstiness survives |
| [0070](0070-more-than-one-injector.md) | a run split across injectors on a user-number partition, aligned on one instant and merged back into the run it was |
| [0074](0074-trends-across-baselines.md) | a series of points, each a population with its own band, and the creep no pair of them can see |
| [0044](0044-one-stylesheet-one-place-to-change-it.md) | the stylesheet asserted once, so a CSS rule moves one golden rather than every page |
| [0047](0047-a-precision-that-travels-with-the-number.md) | a bucket width carried on the frozen value, refused across a merge, and read off the number a page prints |
| [0073](0073-exporting-what-a-run-measured.md) | a run's measurements as an HdrHistogram log, an OpenMetrics exposition and an OTLP push — the judgement left in the report |
| [0071](0071-grpc-steps.md) | gRPC steps over a caller's own stubs, named by the descriptor, with the status a value and no transport chosen for them |
| [0060](0060-kafka-and-the-answer-on-another-topic.md) | Kafka produce steps, and the answer read off another topic by a correlation header |
| [0061](0061-what-the-kafka-path-costs.md) | what the Kafka adapter costs, and a fake broker that satisfies the real client over a socket |

## Drafted, not built

Nothing. Every drafted spec is built, bar the tails listed below.

Unbuilt tails of specs otherwise landed:
`0029-tag`, which is the release itself, `0071`'s server-streaming half, whose
samples are either time-to-the-kth or cadence and belong under a verb naming
which, and `0061-containers`, which the fake broker made largely redundant and
which cannot be verified without a Docker daemon.

## Known and unwritten

Real, small, and not worth a spec each until someone wants them: per-stage
results, OpenAPI import for the Pelican module, streaming request bodies, and
server-sent events beside the WebSocket transport.
