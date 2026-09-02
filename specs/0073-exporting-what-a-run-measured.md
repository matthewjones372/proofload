# 0073 — Exporting what a run measured

## Problem

A run's numbers exist only in formats this repository invented:
`kestrel-baseline`'s tab-separated lines, and the JSON `kestrel-report-html`
inlines in a `<script>`. Neither is readable by the Grafana, Prometheus, OTel
backend and `HistogramLogAnalyzer` a team already runs, so the comparison that
matters — what the client observed against what the server recorded — is done
by eye across two tabs. 0023 built the sending half of that join, a
`traceparent` on every request; there is no metrics half.

## Not doing

- **No scrape or push during a run.** An endpoint pulled while requests depart
  puts a serialisation pass and a reader's lock on the injector; nothing on the
  measured path may be moved by a watcher. All of this reads a frozen result.
- **No dashboard.** What a team draws is theirs; a bundled one is a Grafana
  version to keep up with.
- **No replacing the baseline format.** It stays what `Runs.readAll` reads and
  what 0037 and 0038 compare. None of these exports round-trips.
- **No third-party dependency in `kestrel-core` or `kestrel-report-html`.** The
  page opens with nothing fetched, and core has a test that fails on one.
- **No exemplars, no metric per request.** `spec-0023-exemplars` is drafted and
  unbuilt; this leaves a place for one. A series per request is 0003's argument
  again: at fifty thousand a second, a memory profile.

## Shape

Two leaf modules over a frozen result. **`kestrel-export`** — core and the JDK
only, its own `NoThirdPartyDependenciesTest` — carries the two formats needing
no library. `writeHistogramLog(path)` writes HdrHistogram's log format: a
`Tag=` line per step per side per clock, the frozen buckets in the compressed
base64 V2 encoding, on `Deflater` and `Base64`. `openMetrics()` returns a
snapshot for a Pushgateway or textfile collector, untimestamped as they require:

```
kestrel_latency_seconds_bucket{step="pay",outcome="ok",clock="service",le="0.020971519"} 2841
kestrel_failures_total{step="pay",reason="HttpStatus(503)"} 41
kestrel_behind_seconds_bucket{le="0.000104447"} 1750
kestrel_hiccups_seconds_bucket{le="0.014680063"} 30
kestrel_machine_info{cores="4",jdk="21.0.3+9",os="Linux",arch="aarch64"} 1
```

**`kestrel-otel`** carries the OpenTelemetry SDK and OTLP exporter, the way
`kestrel-pelican` carries `pelican-core`. `sendOtlp(endpoint)` sends the same
measurements as one delta export and returns whether the collector took them
rather than throwing — 0007's reason: a test that dies because the collector
was down is one people stop running.

## Why this shape

The hlog costs almost nothing: `indexOf` and `highestEquivalentOf` are
HdrHistogram's `countsArrayIndex` and `highestEquivalentValue`, so `Histogram`'s
256 sub-buckets are exactly two significant digits and nothing is re-bucketed.
A leaf module may depend on HdrHistogram; recommend not — `Deflater` and
`Base64` are in the JDK, and the library belongs on the **test** classpath as
the oracle, which the gate allows: a module hands it only its *main* classpath.

Prometheus is exact where it matters and lossy in one way worth saying out
loud: log-linear buckets *are* explicit buckets, but `histogram_quantile()`
interpolates *inside* one, the interpolation 0003 refuses. A query language
cannot be stopped, so `# HELP` says a quantile off these is good to 0.78% and
no better. There is no `_sum` either: `Histogram` adds no latencies up.

`behind` and `hiccups` get series of their own, so the injector's honesty
travels with the numbers rather than staying on the HTML page.
`Floor.resolution` does not travel — a judgement, and not on `RunResult` anyway
— nor do the plan, the goals and their `Verdict`s, 0021's intervals,
`CannotTell`, the steady segment or the overflow count `Timing` drops: a series
meaning "this might be noise" gets acted on as though it did not. **The export
carries measurements, the report carries judgement.** Reasons are measurements,
which 0063 makes safe as labels: `MAX_REASONS_PER_STEP` bounds the cardinality.

OTel is where the halves meet, and the ordering is the argument: the metrics
export lands now as an explicit-bucket `Histogram` point with delta temporality
— never an `ExponentialHistogram`, whose geometric boundaries would move counts
across log-linear ones. When `spec-0023-exemplars` lands and `Bucket` gains a
trace id, the exporter reads it as an OTel exemplar and a p99 clicks through.

## Stack

- [x] **`spec-0073-hlog`** — the module, its dependency test, and the writer.
      Done when: HdrHistogram's `HistogramLogReader`, on the test classpath,
      reads a run back with every count equal and p99 `Timing.p99`.
- [x] **`spec-0073-openmetrics`** — `openMetrics()`, against a golden.
      Done when: the golden matches, buckets are cumulative and end in `+Inf`,
      two reasons are two series, and `behind` and `hiccups` are both there.
- [ ] **`spec-0073-otel`** — `kestrel-otel`, carrying the SDK, and `sendOtlp`.
      Done when: a run sent at a collector that is not there returns a refusal
      naming it rather than throwing, and against a recording collector the
      boundaries and counts equal the exposition's.
- [ ] **`spec-0073-docs`** — both modules in `docs/modules.md`, and a page
      saying what each export carries and what it leaves in the report.
      Done when: both rows are named and proven by a test below them, and the
      page carries the interpolation caveat.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Strict OpenMetrics or Prometheus's looser text format?** As far as I can
    read its grammar, OpenMetrics has no free-form comment line, so every
    caveat lives in `# HELP`. Recommend strict: it is what stays valid.
    **Built strict but for one thing, and the exception is deliberate**: there
    is no `_sum`, which a strict reading wants on a histogram. Nothing here
    adds latencies up, so the only way to emit one is to compute it from bucket
    tops — a number nobody measured, printed with the same confidence as the
    counts beside it. Prometheus's own parser takes the exposition as written.
    Where strictness and "a number in a report is a measurement" disagreed,
    this went with the second.
2. **Does the hlog carry the timeline too?** It is what makes
    `HistogramLogAnalyzer` draw something, but those histograms are coarse, and
    one file of two precisions misleads. Recommend a separate call and file.
3. **Should HdrHistogram be a real dependency of `kestrel-export`?** Recommend
    test-only — but that is the decision to reverse first if hand-encoding V2
    runs past its share of the stack. **Held when built**: the encoder came to
    about 150 lines of `Deflater`, `Base64` and zig-zag, and the library on the
    test classpath reading it back is a stronger check than sharing an
    implementation would have been. `Histogram` gained `slotOf`, `slots` and
    `subBuckets` so the layout is stated once rather than copied into the
    exporter — that copy, not the encoding, was the part worth avoiding.
4. **Delta or cumulative for OTLP?** Recommend delta: cumulative claims a
    process-lifetime counter with restart detection, and a run is one window
    with a start and an end `RunResult` already knows.
5. **Does anything carry the run's identity?** Ten runs on one dashboard have to
    be told apart and `startedAt` is all that separates them. Recommend a `run`
    label defaulting to it, not a generated id nothing else here has.
