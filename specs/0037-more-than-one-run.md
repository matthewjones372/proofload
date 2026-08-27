# 0037 — More than one run

## Problem

0021 compares a run to a baseline and states the sampling interval of each
percentile, which is the honest version of comparing two numbers. It leaves the
larger source of movement unmeasured: the difference between two *processes*.

On the JVM the process is the unit of replication. JIT profile, code cache and
heap layout differ per JVM, and the same benchmark on the same VM reaches a
steady state in some processes and not others `[WARMUP]`. Georges et al. put the
protocol plainly: multiple invocations, discard the first, and report an
interval across them `[REPLICATION]`. A single run cannot see any of that, so a
comparison of two single runs attributes process variance to the code.

The building blocks are here. Histograms merge. 0021 wrote a file format. What
is missing is a value that holds several runs and answers as one.

## Not doing

- No statistics on top. The interval across runs is 0038's; this spec is the
  container and the merge.
- No process forking here. The launcher is worth its own argument; this spec
  makes a forked run's output mergeable, which is the part everything else
  needs.
- No history. One set of runs, not twenty over a month.
- No merging unlike runs. Two different plans are two different questions.

## Shape

```kotlin
val runs = Runs.readAll(Path.of("build/kestrel"))   // one file per invocation
runs.size                        // 10
runs.merged[pay].serviceTime.p99 // the percentile of the merged population
runs.each.map { it[pay].serviceTime.p99 }   // ten values, for anything that needs them
```

- `Runs` — several `RunResult`s of the same plan. Keeps them individually **and**
  merged, because the two answer different questions and 0038 needs both.
- `Runs.merged` — histograms added, then percentiles taken. Merging buckets and
  then reading a percentile is correct; averaging percentiles is not
  `[PERCENTILES]`, and the report should never be able to do the second.
- Merging runs of different plans fails, naming what differs, the way 0030
  refuses to compare unlike baselines.

## Why this shape

Keeping the individual runs is the decision worth arguing. A merged result alone
is smaller and answers the common question, and it also hides that it came from
ten runs, which is exactly the information an interval is made of. Keeping both
costs a list of frozen values and makes the next spec possible without a second
pass over the files.

Which end to spend repetitions on has an answer in the literature and it is not
"more of everything": spend them where the variance is, and for JVM work the
variance is between processes rather than inside them `[SPEND]`. That argues for
more short runs over fewer long ones, and it is worth saying in the docs beside
this.

## Stack

- [x] **`spec-0037-runs`** ([#24](https://github.com/matthewjones372/kestrel/pull/24)) — `Runs`, the merge, and refusing unlike plans.
      Done when: ten runs of one plan merge to a result whose count is the sum,
      whose p99 is read from the merged buckets, and whose merge with a
      different plan fails naming the difference.
- [x] **`spec-0037-files`** ([#25](https://github.com/matthewjones372/kestrel/pull/25)) — reading a directory of results written by 0021's
      format, and a `main` that runs a simulation once and writes one.
      Done when: ten JVM invocations produce ten files that read back as one
      `Runs`.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Does the first run get discarded?** The classic protocol discards it
    `[REPLICATION]`. Recommend not discarding silently: keep it, mark it, and let
    0038 decide. A tool that quietly drops data is one nobody can check.
2. **Does `Runs` need its own report, or does the existing page take it?**
    Recommend the page takes a `Runs` and says how many runs it drew, since a
    percentile with ten runs behind it should not look like one with one.
3. **Where does the forking launcher live?** Recommend `kestrel-engine`, later,
    and note that CI can do the loop today with a `main` and a shell for-loop.
    Saying that in the docs is most of the value at a fraction of the code.
4. **Does `Runs` carry the machine?** Yes, per 0030, and refuse to merge across
    different machines rather than warn: unlike 0021's comparison, a merge would
    silently pool them.
