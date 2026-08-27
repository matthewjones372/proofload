# Cookbook

Recipes that are a workflow file and a few lines of Kotlin rather than a
feature. Nothing here needs a service, an account or an action of ours.

## A baseline in GitHub Actions

A baseline is a file. Comparing this run to the last one means having that file
on the runner, and GitHub gives you three places to keep it. Start with the
cache; the other two are for when you need a copy that outlives it or one a
human can look at.

### The job

One JVM invocation does the whole thing: run the simulation, compare it to
whatever baseline was restored beside it, write the comparison into the job
summary, and leave this run behind as the next one's baseline.

```kotlin
import io.github.matthewjones372.kestrel.Change
import io.github.matthewjones372.kestrel.Comparison
import io.github.matthewjones372.kestrel.against
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.baseline.readBaseline
import io.github.matthewjones372.kestrel.baseline.writeBaseline
import io.github.matthewjones372.kestrel.calibratedBy
import io.github.matthewjones372.kestrel.engine.Kestrel
import io.github.matthewjones372.kestrel.http.http
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.report.appendToStepSummary
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.step
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.minutes

val pay = step("pay")
val api = http.baseUrl("https://orders.internal")
val paying = scenario("paying") { exec(pay, api.get("/pay")) }

fun main() {
    val baseline = Path.of("build/kestrel/paying.kestrel")
    val kestrel = Kestrel()

    // Before the load, not after: this is what the machine could do while
    // nothing else was asked of it.
    val floor = kestrel.calibrate()
    val result = kestrel.run(paying.at(200.perSecond, over = 2.minutes)).calibratedBy(floor)

    val previous = baseline.takeIf { Files.exists(it) }?.let(::readBaseline)
    val comparison = result.against(previous)

    result.appendToStepSummary(comparison, floor)
    result.writeBaseline(baseline)

    // Failures fail the job. Latency does not — see below.
    if (result.failed > 0L) exitProcess(1)
}
```

```kotlin
// build.gradle.kts
dependencies {
    // Whatever you have published or built with ./gradlew publishToMavenLocal;
    // nothing is on Maven Central yet.
    implementation("io.github.matthewjones372:kestrel-core:$kestrelVersion")
    implementation("io.github.matthewjones372:kestrel-engine:$kestrelVersion")
    implementation("io.github.matthewjones372:kestrel-http:$kestrelVersion")
    implementation("io.github.matthewjones372:kestrel-baseline:$kestrelVersion")
    implementation("io.github.matthewjones372:kestrel-report-github:$kestrelVersion")
}
```

`appendToStepSummary` writes to the file `GITHUB_STEP_SUMMARY` names, and does
nothing at all off Actions, so the same `main` runs on a laptop.

There is a working copy of this in the repository:
[`AgainstTheBaseline.kt`](../examples/src/main/kotlin/io/github/matthewjones372/kestrel/examples/AgainstTheBaseline.kt),
run by [`baseline.yml`](../.github/workflows/baseline.yml) against a JDK
`HttpServer` in the same process.

### Keeping it: the cache

```yaml
- name: Restore the last baseline
  uses: actions/cache@v4
  with:
    path: build/kestrel
    key: kestrel-baseline-${{ github.run_id }}
    restore-keys: kestrel-baseline-
```

The key is a run id, so it never hits and the cache is always written fresh at
the end of the job. The prefix `restore-keys` is what finds the newest one that
was written under any earlier run id. That pair is the whole trick: a key that
never matches and a prefix that always does gives you a rolling baseline rather
than one frozen the first time it was saved.

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
    name: kestrel-baseline
    path: build/kestrel/*.kestrel
    retention-days: 90
    if-no-files-found: error
```

and fetch the newest successful one before the run:

```yaml
- name: Fall back to the last baseline main published
  env:
    GH_TOKEN: ${{ github.token }}
  run: |
    mkdir -p build/kestrel
    run_id=$(gh run list --branch main --workflow baseline.yml --status success \
      --limit 1 --json databaseId --jq '.[0].databaseId')
    gh run download "$run_id" --name kestrel-baseline --dir build/kestrel \
      || echo "no baseline published yet; this run will write the first one"
```

The `||` matters. A missing baseline is a thing to report, not a thing to die
of — and Kestrel reports it, so let the step pass and let the summary say it.

### Keeping it: a branch somebody reviews

A cache and an artifact are both invisible until something breaks. Where the
baseline is a number the team argues about — the rate a service is expected to
hold — put it on an orphan branch and change it in a pull request:

```bash
git switch --orphan kestrel-baseline
git rm -rf .
cp build/kestrel/paying.kestrel .
git add paying.kestrel && git commit -m "chore: baseline for paying at 200/s"
git push origin kestrel-baseline
```

and read it in the job, which needs no checkout of it:

```yaml
- name: Fetch the reviewed baseline
  run: |
    mkdir -p build/kestrel
    git fetch --depth 1 origin kestrel-baseline
    git show FETCH_HEAD:paying.kestrel > build/kestrel/paying.kestrel
```

Then stop writing it from the job: a baseline somebody reviews is one only a
pull request changes. Moving it is then a diff with a person's name on it,
which is the point.

## Do not gate a merge on latency

On a shared runner, gate on failures and errors. Do not gate on latency.

GitHub-hosted runners are shared machines. The same code, the same commit and
the same rate measure differently between two runs of them, and this repository's
own benchmark shows p99 tails dominated by machine stalls rather than by load. A
threshold on p99 therefore fails some fraction of pull requests for reasons
nobody can act on — and a gate that fails a third of the time gets deleted
within a month, taking the failure and error checks with it.

So:

- **Fail the job** on failed requests, on errors, and on a step that stopped
  running at all. Those are the same on any machine.
- **Report** latency. The job summary carries the comparison; a reviewer reads
  it and decides.
- If you want a latency gate, run it on a machine you own, where the same code
  measures the same twice, and keep it out of the merge path.

## What the comparison will refuse to say

`against` answers `Comparison.NotComparable` rather than inventing a delta:

| It says | When |
|---|---|
| `no baseline to compare against` | the cache missed, or this is the first run |
| `these runs were not asked to do the same thing` | a different scenario, steps, or rate line |

and `Comparison.Compared` carries a `caveat` where the two runs are comparable
but something about the machines argues against the numbers. A calibration
probe — the same target-free measurement on both machines — is what turns "this
might be the runner" into `slowdown: 2.0`. Past a quarter slower the caveat
leads with it, above any step, because the runner is then the likelier
explanation of everything under it.

Two probes are the same work timed twice, which is the one cross-machine
comparison that is like for like. It is not `resolution`, which is the *spread*
of those repeats as a fraction of a null step's own tiny median.

## What calibration costs

`calibrate()` is bounded at thirty seconds, measured once per JVM and kept. On a
runner whose spread you have already measured, name it instead:

```yaml
- run: ./gradlew :examples:againstTheBaseline -Dkestrel.resolution=0.05
```

That skips the measurement, and with it the probe — a floor somebody typed has
no probe behind it, so nothing can compare the runner to the baseline's. Skip
it where the runners are identical and known; measure it where they are not,
which on hosted runners is most of the time.
