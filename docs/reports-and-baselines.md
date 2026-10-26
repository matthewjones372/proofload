# Reports, baselines and comparisons

A `RunResult` can be written out for people (an HTML page, a GitHub job
summary), kept as a baseline for the next run to be compared with, or exported
for other tools ([exporting.md](exporting.md)). This page covers the first two,
and how to compare runs without mistaking noise for a regression.

## Write an HTML report

`proofload-report-html` writes one self-contained file, with the data, the
stylesheet and the script inline, so it opens from disk and uploads as a CI
artifact unchanged.

```kotlin
import io.github.matthewjones372.proofload.report.writeHtmlReport

result.writeHtmlReport(Path.of("build/reports/proofload/checkout.html"))
```

```
proofload: report written to build/reports/proofload/checkout.html
proofload:   file:///home/you/project/build/reports/proofload/checkout.html
```

The page shows the goals and their verdicts first, then the plan and what was
actually sent, a "behind schedule" banner when `fellBehind()` is true, goodput,
the injector's own stalls, failures by reason, a per-step table on either clock,
each step's latency distribution, the timeline, and `p999` where there are
enough samples. A closed workload is labelled at the top.

Optional arguments add sections without changing any number:

```kotlin
result.writeHtmlReport(
    path,
    comparison = result.against(readBaseline(path)),   // this run against the last
    floor = proofload.calibrate(),                       // what this machine can resolve
    differences = listOf(difference),                  // several runs a side
)
```

A capacity search has a page of its own, with the curve on it:

```kotlin
capacity.writeHtmlReport(Path.of("build/reports/proofload/capacity.html"))
```

From Scala the same calls are `result.writeHtmlReport(path)`, `result.markdown`
and `result.appendToStepSummary()`, and in a zio-test spec
`proofload.writeHtmlReport(result, path)` and the rest are effects.

## Publish the reports to GitHub Pages

The HTML report is one self-contained file, so publishing is a copy and an
index:

```kotlin
import io.github.matthewjones372.proofload.report.writePagesIndex

val directory = Path.of("build/pages")
result.writeHtmlReport(directory.resolve("checkout.html"))
capacity.writeHtmlReport(directory.resolve("capacity.html"))

writePagesIndex(directory)   // an index.html linking whatever is there
```

```yaml
- uses: actions/upload-pages-artifact@v3
  with:
    path: build/pages
- uses: actions/deploy-pages@v4
```

The index is generated from the directory, so a report that stops being written
stops being linked.

## A GitHub job summary

`proofload-report-github` renders a run as markdown: the step table, the
schedule verdict, failures and totals. `appendToStepSummary` adds it to the
Actions job summary:

```kotlin
import io.github.matthewjones372.proofload.report.appendToStepSummary

result.appendToStepSummary(comparison = comparison, floor = floor)
```

Off Actions it writes nothing and returns `StepSummary.NotOnActions` rather than
throwing, so the same code runs on a laptop. `result.markdown(...)` returns the
same text as a string.

## Baselines

`proofload-baseline` writes a run to a file and reads it back:

```kotlin
import io.github.matthewjones372.proofload.against
import io.github.matthewjones372.proofload.baseline.readBaseline
import io.github.matthewjones372.proofload.baseline.writeBaseline

result.writeBaseline(Path.of("build/proofload/checkout.proofload"))

val comparison = result.against(readBaseline(Path.of("build/proofload/checkout.proofload")))
```

`against` compares p99 of response time per step and answers `Comparison.Compared`
or `Comparison.NotComparable`. Each step comes back as one of `Better`, `Worse`,
`Indistinguishable`, `Added` or `Gone`. Runs of different plans are refused, and
runs from different machines are compared with a caveat
([below](#what-the-comparison-will-refuse-to-say)). `of = Clock.ServiceTime`
compares service time instead.

**Discard a run before keeping one.** The first run in a JVM pays for class
loading, JIT compilation and opening connections, and can measure several times
slower than the runs after it with no change to the target. `proofload-baseline`
does not drop it for you.

## A baseline in GitHub Actions

Comparing this run with the last one means having the baseline file on the
runner. GitHub offers three places to keep it. Start with the cache; the other
two are for a copy that outlives it or one a person reviews.

### The job

One JVM invocation does the whole thing: run the simulation, compare it to
whatever baseline was restored beside it, write the comparison into the job
summary, and leave this run behind as the next one's baseline.

```kotlin
import io.github.matthewjones372.proofload.Change
import io.github.matthewjones372.proofload.Comparison
import io.github.matthewjones372.proofload.against
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.baseline.readBaseline
import io.github.matthewjones372.proofload.baseline.writeBaseline
import io.github.matthewjones372.proofload.calibratedBy
import io.github.matthewjones372.proofload.engine.Proofload
import io.github.matthewjones372.proofload.http.http
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.report.appendToStepSummary
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.step
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.minutes

val pay = step("pay")
val api = http.baseUrl("https://orders.internal")
val paying = scenario("paying") { exec(pay, api.get("/pay")) }

fun main() {
    val baseline = Path.of("build/proofload/paying.proofload")
    val proofload = Proofload()

    // Before the load, not after: this is what the machine could do while
    // nothing else was asked of it.
    val floor = proofload.calibrate()
    val result = proofload.run(paying.at(200.perSecond, over = 2.minutes)).calibratedBy(floor)

    val previous = baseline.takeIf { Files.exists(it) }?.let(::readBaseline)
    // p99 of response time.
    val comparison = result.against(previous)

    result.appendToStepSummary(comparison, floor)
    result.writeBaseline(baseline)

    // Failures fail the job. Latency does not; see below.
    if (result.failed > 0L) exitProcess(1)
}
```

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.matthewjones372:proofload-core:0.1.0-rc5")
    implementation("io.github.matthewjones372:proofload-engine:0.1.0-rc5")
    implementation("io.github.matthewjones372:proofload-http:0.1.0-rc5")
    implementation("io.github.matthewjones372:proofload-baseline:0.1.0-rc5")
    implementation("io.github.matthewjones372:proofload-report-github:0.1.0-rc5")
}
```

`appendToStepSummary` writes to the file `GITHUB_STEP_SUMMARY` names, and does
nothing at all off Actions, so the same `main` runs on a laptop.

There is a working copy of this in the repository:
[`AgainstTheBaseline.kt`](../examples/src/main/kotlin/io/github/matthewjones372/proofload/examples/AgainstTheBaseline.kt),
run by [`baseline.yml`](../.github/workflows/baseline.yml) against a JDK
`HttpServer` in the same process.

### Keeping it: the cache

```yaml
- name: Restore the last baseline
  uses: actions/cache@v4
  with:
    path: build/proofload
    key: proofload-baseline-${{ github.run_id }}
    restore-keys: proofload-baseline-
```

The key is a run id, so it never hits and the cache is written fresh at the end
of every job, while the `restore-keys` prefix finds the newest one written by an
earlier run. Together they give a rolling baseline.

A pull request reads the cache its base branch wrote and writes only into its
own scope, so the comparison is against `main` and one branch cannot poison
another's. A cache nothing reads for seven days is evicted.

### Keeping it: an artifact

Eviction is the cache's failure mode, and a workflow that wants a baseline from
a different workflow cannot use a cache at all. Upload the file:

```yaml
- name: Keep the baseline where a later run can fetch it
  if: always()
  uses: actions/upload-artifact@v7
  with:
    name: proofload-baseline
    path: build/proofload/*.proofload
    retention-days: 90
    if-no-files-found: error
```

and fetch the newest successful one before the run:

```yaml
- name: Fall back to the last baseline main published
  env:
    GH_TOKEN: ${{ github.token }}
  run: |
    mkdir -p build/proofload
    run_id=$(gh run list --branch main --workflow baseline.yml --status success \
      --limit 1 --json databaseId --jq '.[0].databaseId')
    gh run download "$run_id" --name proofload-baseline --dir build/proofload \
      || echo "no baseline published yet; this run will write the first one"
```

The `||` lets the step pass when there is no baseline yet; the comparison then
reports `no baseline to compare against`.

### Keeping it: a branch somebody reviews

A cache and an artifact are both invisible until something breaks. Where the
baseline is a number the team argues about, the rate a service is expected to
hold, put it on an orphan branch and change it in a pull request:

```bash
git switch --orphan proofload-baseline
git rm -rf .
cp build/proofload/paying.proofload .
git add paying.proofload && git commit -m "chore: baseline for paying at 200/s"
git push origin proofload-baseline
```

and read it in the job, which needs no checkout of it:

```yaml
- name: Fetch the reviewed baseline
  run: |
    mkdir -p build/proofload
    git fetch --depth 1 origin proofload-baseline
    git show FETCH_HEAD:paying.proofload > build/proofload/paying.proofload
```

Then stop writing it from the job, so only a pull request changes it.

## What the comparison will refuse to say

`against` answers `Comparison.NotComparable` rather than inventing a delta:

| It says | When |
|---|---|
| `no baseline to compare against` | the cache missed, or this is the first run |
| `these runs were not asked to do the same thing` | a different scenario, steps, rate line, or drawn data both runs named |

and `Comparison.Compared` carries a `caveat` where the two runs are comparable
but something about the machines argues against the numbers. A calibration
probe (the same target-free measurement on both machines) is what turns "this
might be the runner" into `slowdown: 2.0`. Past a quarter slower the caveat
leads with it, above any step, because the runner is then the likelier
explanation of everything under it.

Two probes are the same work timed twice, which is the one cross-machine
comparison that is like for like. It is not `resolution`, which is the *spread*
of those repeats as a fraction of a null step's own tiny median.

## More than one run, and a verdict worth having

A single run cannot bound its own noise, so a comparison of one run against one
baseline can say what moved but not whether the move was real.

`Runs` is several runs of one plan. `against` compares two populations by
resampling (a bootstrap over the runs), so the spread it judges by is the spread
actually observed:

```kotlin
import io.github.matthewjones372.proofload.Runs
import io.github.matthewjones372.proofload.against
import io.github.matthewjones372.proofload.baseline.readAll
import io.github.matthewjones372.proofload.baseline.writeInto
import io.github.matthewjones372.proofload.junit5.assertNotWorseThan

val before = Runs.readAll(Path.of("baselines/checkout"))
val now = Runs(List(5) { proofload.run(checkout.at(50.perSecond, over = 2.minutes)) })

now.against(before, p99(placeOrder), acceptable = 10.percent)
    .assertNotWorseThan(10.percent)
```

In Kotest, the same threshold as a matcher:

```kotlin
import io.github.matthewjones372.proofload.kotest.NotWorseThan

now.against(before, p99(placeOrder)) should NotWorseThan(10.percent)
```

It needs at least five runs a side, and refuses with a reason below that.

The verdict is `Better`, `Worse` or `CannotTell`. `CannotTell` carries why it
could not tell and what would change that.

By default a `CannotTell` passes the assertion. Ask for the stricter reading
where you would rather stop and look:

```kotlin
difference.assertNotWorseThan(10.percent, orCannotTell = true)
```

Each run is one file, so a shell loop that forks a JVM per run leaves a
directory `readAll` can pick up:

```bash
for i in $(seq 1 5); do ./gradlew :examples:soak; done   # each writes one file
```

```kotlin
result.writeInto(Path.of("baselines/checkout"))   // run-<started>-<pid>.proofload
```

The start time and process id together keep the names distinct.

## What a statistic has been doing

A pairwise comparison cannot see a slow creep: a p99 drifting two percent per
build stays inside every interval. A trend reads a series of points instead.

A trend is a directory of directories: one subdirectory per point, holding that
point's runs. The subdirectory name is the label:

```bash
for i in $(seq 1 5); do ./gradlew :examples:soak; done   # into history/$GITHUB_SHA/
```

```kotlin
import io.github.matthewjones372.proofload.baseline.readTrend
import io.github.matthewjones372.proofload.report.writeHtmlReport

val trend = readTrend(Path.of("history"), p99(pay), acceptable = 5.percent)

trend.ends          // oldest point against newest, where a creep shows
trend.steps         // the adjacent pairs whose own runs support a move
trend.comparisons   // how many were made, so the page can say so

trend.writeHtmlReport(Path.of("build/reports/trend.html"))
```

Nothing is fitted and nothing is drawn between points nobody ran. A change of
machine is a break in the series, because `Runs` refuses to merge unlike
machines.

Read the count beside the steps. Thirty-nine adjacent comparisons at 95%
confidence are expected to name about two steps in a series that never moved,
so a named step is a place to look rather than a finding. Intervals are not
widened for the number of comparisons.

## What calibration costs

`calibrate()` takes up to thirty seconds and runs once per JVM. On a
runner whose spread you have already measured, name it instead:

```yaml
- run: ./gradlew :examples:againstTheBaseline -Dproofload.resolution=0.05
```

That skips the measurement, and with it the probe, so the comparison can no
longer tell whether this runner is slower than the baseline's. Skip it only
where the runners are identical and known.

## Do not gate a merge on latency

On a shared runner, gate on failures and errors. Do not gate on latency.

GitHub-hosted runners are shared machines. The same commit at the same rate
measures differently between two runs, and tails on shared machines are often
dominated by machine stalls rather than load. A p99 threshold will fail some
pull requests for reasons nobody can act on.

So:

- **Fail the job** on failed requests, on errors, and on a step that stopped
  running at all. Those are the same on any machine.
- **Report** latency. The job summary carries the comparison; a reviewer reads
  it and decides.
- If you want a latency gate, run it on a machine you own, where the same code
  measures the same twice, and keep it out of the merge path.

## Send the numbers somewhere else

A run's measurements in formats other tools read: an HdrHistogram log, a
Prometheus or OpenMetrics exposition, and an OTLP push at a collector.

```kotlin
import io.github.matthewjones372.proofload.export.writeHistogramLog
import io.github.matthewjones372.proofload.export.writeOpenMetrics
import io.github.matthewjones372.proofload.otel.sendOtlp

result.writeHistogramLog(Path.of("build/proofload/run.hlog"))
result.writeOpenMetrics(Path.of("/var/lib/node_exporter/proofload.prom"))
result.sendOtlp("http://collector:4318/v1/metrics")
```

All three read the finished result. They carry the measurements (latencies, the
generator's lateness, the injector's stalls, failures by reason) but not goals or
verdicts; the JSON run document carries those.

Read [exporting.md](exporting.md) before writing a query against them:
`histogram_quantile()` interpolates inside a bucket, which these numbers do not
support, and there is no `_sum`.
