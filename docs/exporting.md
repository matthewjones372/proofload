# Exporting what a run measured

`proofload-export` and `proofload-otel` write a finished run in formats other
tools read:

| Export | For | Carries |
|---|---|---|
| the run document (JSON) | a program that has to decide something from the run: a CI step, an agent | the verdict, the schedule, every goal, the measurements |
| HdrHistogram log | `HistogramLogAnalyzer`, `HistogramLogProcessor` | latency histograms |
| OpenMetrics exposition | Prometheus, via a Pushgateway or textfile collector | latency histograms, lateness, failures, machine |
| OTLP push | an OpenTelemetry collector | the same measurements as OpenMetrics |

All of them read the finished `RunResult`. Nothing is scraped or pushed while
requests are departing, because a reader on the recorders would add work to the
path being timed. (Live counts during a run are a separate, deliberately
limited feature: [running.md](running.md#watch-a-two-hour-soak-from-a-dashboard).)

## Measurements, and where judgement goes

**A metrics export carries measurements. Verdicts travel only in the run
document, which is read whole.**

What travels: the latency histograms, per step, per outcome, per clock; the
run's own lateness; what the injector's JVM stalled for; requests and failures
by reason; and what machine measured it.

What does not: the plan, the goals and their verdicts, the sampling intervals,
the steady segment, the machine's floor, and every "cannot tell". Those are
readings *about* the numbers, and a series meaning "this might be noise" is a
series that gets alerted on as though it were not. They stay on the page, where
the sentence next to them survives.

## The run itself, as a document

```kotlin
import io.github.matthewjones372.proofload.export.Density
import io.github.matthewjones372.proofload.export.json
import io.github.matthewjones372.proofload.export.writeJson

println(result.json(Density.Summary))            // the answer, under two kilobytes
result.writeJson(Path.of("build/run.json"))      // Density.Full, every step
```

`Summary` is what the run concluded and nothing else: a one-word `verdict`, the
plan, whether the schedule held, every goal with what it measured and the margin
it missed by, the steady segment, [Little's law](measurement.md#littles-law), the counts, and the failures
folded together by reason. `Full` adds the per-step timings and the timeline.
Durations are the nanoseconds the histogram reported. The document holds the
measurement, the reader does the formatting.

The `verdict` is the first of these that applies:

| | |
|---|---|
| `behind` | `fellBehind()` or `lostGround()` was true, so response times include the generator's own queue. This outranks everything, including a goal that also missed |
| `nothingAsked` | the run carried no goals. Not `met`: a run asked nothing met nothing |
| `missed` | a goal missed, and `goals[].overBy` says by how much |
| `cannotTell` | nothing missed, but something could not be judged at the resolution available; `cannotTell.wouldChangeIt` says what would fix that |
| `met` | every goal asked was met |

Beside the verdict is a **`remedy`**: one sentence saying what to do, chosen in
the same order the verdict is: the schedule's if the generator lost it, then
the first goal that definitely missed. A refused goal's remedy is its own
`wouldChangeIt` rather than a second sentence written beside it. No remedy
names a rate nobody measured: a suggested number would be an estimate printed
as advice. `Density.Full` repeats the sentence on each goal; the summary states
it once.

Goals are judged over the whole run here, not over the steady segment that
`result.verdicts` uses, so the two can disagree about a goal near its limit.

Every document names its schema in its first field, and a reader must ignore
fields it does not know: a new optional key is not a breaking change, and the
version only moves when an existing one changes meaning.

The shape is written down in [docs/schemas/run-1.json](schemas/run-1.json), and
both documents above are validated against it on every build. It forbids
undeclared properties, which is the producer's half of the promise. A field
Proofload emits without declaring is a build failure. That is not the reader's
rule, which stays "ignore what you do not know".

**Why verdicts are in the document and not in the metrics.** A time-series
backend separates a number from the caveat beside it. `proofload_behind_seconds`
scraped into Prometheus no longer says "and therefore the tail is not the
target's". A document is read whole, with the caveat in the field beside the
number.

## An HdrHistogram log

```kotlin
import io.github.matthewjones372.proofload.export.writeHistogramLog

result.writeHistogramLog(Path.of("build/proofload/run.hlog"))
```

One tagged line per step per side per clock (`pay.ok.service`,
`pay.failed.response`) plus `behind` and `hiccups`. `HistogramLogAnalyzer` and
`HistogramLogProcessor` read it directly.

Nothing is re-bucketed. Proofload's counter layout is HdrHistogram's: 256
sub-buckets is two significant digits and 32 is one, a slot's index is its
`countsArrayIndex` and a bucket's top its `highestEquivalentValue`. So a
percentile read out of the other tool is the percentile read out of this one,
and a test asserts exactly that, bucket for bucket, using HdrHistogram's own
reader as the oracle.

Only the step summaries are written. The timeline is counted at a coarser
precision, and one file holding two precisions would not say which line is
which.

## A Prometheus or OpenMetrics exposition

```kotlin
import io.github.matthewjones372.proofload.export.writeOpenMetrics

result.writeOpenMetrics(Path.of("/var/lib/node_exporter/proofload.prom"))
```

For a Pushgateway or a textfile collector to pick up once the run is over,
which is why there are no timestamps. Both attach their own.

```
proofload_latency_seconds_bucket{run="…",step="pay",outcome="ok",clock="service",le="0.020971519"} 2841
proofload_behind_seconds_bucket{run="…",le="0.000104447"} 1750
proofload_failures_total{run="…",step="pay",reason="status 503"} 41
proofload_machine_info{cores="4",jdk="21.0.2+13",os="Linux",arch="aarch64"} 1
```

**The caveat worth reading before you write a query.** These are explicit
buckets, and they are the histogram's own. Every sample is counted at the top
of the bucket it fell in, so a quantile off them is good to 0.78% and no
better. `histogram_quantile()` interpolates inside a bucket, which Proofload
does nowhere else. Read what it gives you as the bucket top it lands in, not as
a point between two.

There is no `_sum`, and so no mean. A sum would have to be computed from bucket
tops, which is not a measurement. This departs from a strict reading of the
OpenMetrics grammar.

A series per failure reason is safe rather than unbounded, because a reason is a
value and the recorder caps how many one step keeps, so this cannot become a series
per request.

## An OpenTelemetry collector

```kotlin
import io.github.matthewjones372.proofload.otel.Sent
import io.github.matthewjones372.proofload.otel.sendOtlp

when (val sent = result.sendOtlp("http://collector:4318/v1/metrics")) {
    Sent.Accepted -> println("the collector took them")
    is Sent.Refused -> println("it did not: ${sent.why}")
}
```

It returns a value rather than throwing, so a collector that is down does not
fail the load test.

**Delta temporality.** A run is one window with a known start and end.

**Explicit buckets, never an exponential histogram.** Proofload's boundaries are
log-linear and an exponential histogram's are geometric, so converting would
move counts across boundaries.

OTel's data model takes a mandatory sum, which nothing here measures. It is
sent as the sum every sample would have made had each one sat at the top of its
own bucket: an upper bound, inside the histogram's own 0.78%, and the metric
description says which it is.

`proofload-otel` carries the OpenTelemetry SDK. It sends OTLP over HTTP with the
SDK's `java.net.http` sender rather than OkHttp, and a test fails if OkHttp or a
gRPC runtime reaches its classpath.

## Telling runs apart

Every export takes a `run` label, defaulting to when the run started, so runs
on one dashboard can be told apart.

```kotlin
result.writeOpenMetrics(path, run = System.getenv("GITHUB_SHA"))
```

## Which module

`proofload-export` needs only core and the JDK. `proofload-otel` carries the
SDK. Taking one does not bring the other.
