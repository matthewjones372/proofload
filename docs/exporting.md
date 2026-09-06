# Exporting what a run measured

A run's numbers are, by default, in two formats this repository invented: the
tab-separated lines a baseline is kept in, and the JSON the HTML report inlines
in a `<script>`. Neither is readable by the Grafana, Prometheus, OpenTelemetry
backend or `HistogramLogAnalyzer` a team already runs, so the comparison that
matters — what the client observed against what the server recorded — gets done
by eye across two tabs.

A fourth export answers a different question. The three metrics formats hand a
run's numbers to a system that already draws them; the run document hands the
run's *answer* to a program that has to decide something.

Three exports fix that. All of them read a frozen `RunResult` after the run is
over: nothing is scraped or pushed while requests are departing, because a
serialisation pass and a reader's lock on the injector would move the thing
being measured.

## The one rule, and where it stops

**A metrics export carries measurements. Judgement travels only in a document
somebody reads whole.**

This governs the three exports below — the histogram log, the OpenMetrics
exposition and the OTLP push. It does not govern the run document, which is a
different shape with a different reader; the section on it says why.

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
import io.github.matthewjones372.kestrel.export.Density
import io.github.matthewjones372.kestrel.export.json
import io.github.matthewjones372.kestrel.export.writeJson

println(result.json(Density.Summary))            // the answer, under two kilobytes
result.writeJson(Path.of("build/run.json"))      // Density.Full, every step
```

`Summary` is what the run concluded and nothing else: a one-word `verdict`, the
plan, whether the schedule held, every goal with what it measured and the margin
it missed by, the steady segment, [Little's law](concepts.md#littles-law-and-what-it-catches), the counts, and the failures
folded together by reason. `Full` adds the per-step timings and the timeline.
Durations are the nanoseconds the histogram reported — the document holds the
measurement, the reader does the formatting.

The `verdict` is ordered rather than scored, and the order is the claim:

| | |
|---|---|
| `behind` | the generator lost its own schedule, so the numbers are not the target's — this outranks everything, including a goal that also missed |
| `nothingAsked` | the run carried no goals. Not `met`: a run asked nothing met nothing |
| `missed` | a goal missed, and `goals[].overBy` says by how much |
| `cannotTell` | nothing missed, but something could not be judged at the resolution available; `cannotTell.wouldChangeIt` says what would fix that |
| `met` | every goal asked was met |

Beside the verdict is a **`remedy`**: one sentence saying what to do, chosen in
the same order the verdict is — the schedule's if the generator lost it, then
the first goal that definitely missed. A refused goal's remedy is its own
`wouldChangeIt` rather than a second sentence written beside it. No remedy
names a rate nobody measured: a suggested number would be an estimate printed
as advice. `Density.Full` repeats the sentence on each goal; the summary states
it once.

Every document names its schema in its first field, and a reader must ignore
fields it does not know: a new optional key is not a breaking change, and the
version only moves when an existing one changes meaning.

The shape is written down in [docs/schemas/run-1.json](schemas/run-1.json), and
both documents above are validated against it on every build. It forbids
undeclared properties, which is the producer's half of the promise — a field
Kestrel emits without declaring is a build failure. That is not the reader's
rule, which stays "ignore what you do not know".

**Why the rule above does not reach here.** The rule exists because a
time-series backend strips the sentence off a number. `kestrel_behind_seconds`
scraped into Prometheus and alerted on has lost "and therefore the tail below is
not the target's" — so the caveat has to stay where the caveat is readable. A
document is read whole, by one reader, with the caveat in the field beside the
number. Splitting the verdict away from it there would not be caution; it would
just be a document that cannot answer the question it was fetched for.

## An HdrHistogram log

```kotlin
import io.github.matthewjones372.kestrel.export.writeHistogramLog

result.writeHistogramLog(Path.of("build/kestrel/run.hlog"))
```

One tagged line per step per side per clock — `pay.ok.service`,
`pay.failed.response` — plus `behind` and `hiccups`. `HistogramLogAnalyzer` and
`HistogramLogProcessor` read it directly.

Nothing is re-bucketed. This tool's counter table *is* HdrHistogram's: 256
sub-buckets is two significant digits and 32 is one, a slot's index is its
`countsArrayIndex` and a bucket's top its `highestEquivalentValue`. So a
percentile read out of the other tool is the percentile read out of this one,
and a test asserts exactly that, bucket for bucket, using HdrHistogram's own
reader as the oracle.

Only the step summaries. The timeline is counted an eighth as wide, and one
file holding two precisions tells its reader nothing about which line is which.

## A Prometheus or OpenMetrics exposition

```kotlin
import io.github.matthewjones372.kestrel.export.writeOpenMetrics

result.writeOpenMetrics(Path.of("/var/lib/node_exporter/kestrel.prom"))
```

For a Pushgateway or a textfile collector to pick up once the run is over,
which is why there are no timestamps — both attach their own.

```
kestrel_latency_seconds_bucket{run="…",step="pay",outcome="ok",clock="service",le="0.020971519"} 2841
kestrel_behind_seconds_bucket{run="…",le="0.000104447"} 1750
kestrel_failures_total{run="…",step="pay",reason="status 503"} 41
kestrel_machine_info{cores="4",jdk="21.0.2+13",os="Linux",arch="aarch64"} 1
```

**The caveat worth reading before you write a query.** These are explicit
buckets, and they are the histogram's own — every sample is counted at the top
of the bucket it fell in, so a quantile off them is good to 0.78% and no
better. `histogram_quantile()` interpolates *inside* a bucket, which is the
interpolation this tool refuses everywhere else. Read what it gives you as the
bucket top it lands in, not as a point between two.

There is no `_sum`, and so no mean to be had by dividing. Nothing here adds
latencies up: the only way to produce one is to compute it from bucket tops,
which is a number nobody measured printed with the same confidence as the
counts beside it. That is the one place this leaves a strict reading of the
OpenMetrics grammar, deliberately.

A series per failure reason is safe rather than unbounded — a reason is a value
and the recorder caps how many one step keeps — so this cannot become a series
per request.

## An OpenTelemetry collector

```kotlin
import io.github.matthewjones372.kestrel.otel.Sent
import io.github.matthewjones372.kestrel.otel.sendOtlp

when (val sent = result.sendOtlp("http://collector:4318/v1/metrics")) {
    Sent.Accepted -> println("the collector took them")
    is Sent.Refused -> println("it did not: ${sent.why}")
}
```

A value rather than an exception, for the same reason a report is written
rather than thrown: a load test that dies because an observability backend was
down is one people stop running, and the measurement it was carrying is the
thing that mattered.

**Delta, not cumulative.** Cumulative claims a counter that has been running
for the life of a process, with the restart detection that implies. A run is
one window with a start and an end the result already knows.

**Explicit buckets, never an exponential histogram.** These boundaries are
log-linear and an exponential histogram's are geometric, so the counts would
have to be moved across boundaries that were measured — the same interpolation
as above, done at export time where nobody would see it.

OTel's data model takes a mandatory sum, which nothing here measures. It is
sent as the sum every sample would have made had each one sat at the top of its
own bucket: an upper bound, inside the histogram's own 0.78%, and the metric
description says which it is.

`kestrel-otel` is the only module here that carries a third-party dependency
for its own sake. It sends OTLP over HTTP on the SDK's `java.net.http` sender
rather than the OkHttp one the exporter ships with, and a test fails if OkHttp
or a gRPC runtime comes back: a load test's own process is the last place to
put a second HTTP client, because the first one is the thing being measured.

## Telling runs apart

Every export takes a `run` label, defaulting to when the run started — the only
thing a result carries that separates it from another. Ten runs on one
dashboard have to be told apart, and a generated id would join to nothing else
here.

```kotlin
result.writeOpenMetrics(path, run = System.getenv("GITHUB_SHA"))
```

## Which module

`kestrel-export` is core and the JDK only — the histogram log and the
exposition need nothing but `Deflater` and `Base64`. `kestrel-otel` carries the
SDK. Taking one does not bring the other, which is the whole point of them
being two.
