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

## Drafted, not built

In the order the architect would take them.

| Spec | Why it is next |
|---|---|
| [0022](0022-a-token-that-stays-fresh.md) | a soak longer than a token's life cannot authenticate |
| [0025](0025-what-happened-when.md) | a run that degraded halfway looks identical to one that did not |
| [0024](0024-think-time-and-closed-model.md) | `pause`, and the closed model with its caveat stated |
| [0026](0026-checks-and-retries.md) | a 200 with an error page in it is counted as a success |
| [0027](0027-the-docs-a-newcomer-needs.md) | `AGENTS.md` points at a `docs/modules.md` that does not exist |
| [0023](0023-a-number-you-can-follow.md) | exemplars, so a p99 leads somewhere |
| [0028](0028-a-run-that-does-not-book-a-million-tasks.md) | the whole run is booked before the first request |
| [0030](0030-a-baseline-in-ci.md) | a baseline from another runner reads as a regression in your service |
| [0029](0029-cutting-0-1-0.md) | a publishing pipeline that has never run does not work |

## Known and unwritten

Real, small, and not worth a spec each until someone wants them: per-stage
results, trends across more than one baseline, OpenAPI import for the Pelican
module, cookies and auth flows in `kestrel-http`, streaming request bodies, and
a warm-up phase that is excluded from the numbers rather than discarded by
hand.
