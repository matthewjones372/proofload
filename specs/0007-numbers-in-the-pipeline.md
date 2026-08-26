# 0007 — Numbers in the pipeline

## Problem

A load test in CI that prints to stdout is a load test nobody reads. The result
is buried in a job log, and the only way to compare last week's p99 with this
week's is to open two logs side by side.

Gatling's answer is a plugin. The bet here is that a workflow file and two
functions beat a plugin, because a workflow file is a thing a team already
knows how to read.

## Not doing

- No Gradle plugin, no GitHub Action, nothing to install.
- No PR comments and no check runs. Both need a token and an API client.
- No thresholds and no build failing. A test asserts; that is JUnit's job.
- No history storage. Pages publishing is a layout, not a database.

## Shape

```kotlin
import io.github.matthewjones372.kestrel.report.appendToStepSummary
import io.github.matthewjones372.kestrel.report.markdown

val result = simulation.run()

println(result.markdown())        // anywhere
result.appendToStepSummary()      // no-op off GitHub Actions
```

```yaml
- run: ./gradlew loadTest
- uses: actions/upload-artifact@v4
  with:
    name: kestrel-report
    path: build/reports/kestrel/
```

- `kestrel-report-github`, depending on `kestrel-core` and the JDK only, with
  its own `NoThirdPartyDependenciesTest`.
- `markdown()` — a summary table, GitHub-flavoured, that also reads fine in a
  terminal or a PR body.
- `appendToStepSummary()` — appends to the file `$GITHUB_STEP_SUMMARY` names.
  **Off Actions, where that variable is unset, it does nothing and says so in
  its return value** rather than throwing or writing somewhere surprising.
- The behind-schedule warning is the first line of the table when it applies.
- A Pages layout: a directory the HTML report and an `index.html` are written
  into, so publishing is a workflow step and not a feature.

## Why this shape

Writing to a file named by an environment variable is the whole GitHub
integration. No token, no API, no plugin, and it works in any workflow that
already runs Gradle. Anything richer — a PR comment, a check run — costs
authentication and buys a nicer surface for the same numbers.

`appendToStepSummary()` returning a value rather than throwing off Actions
matters: the same test runs on a laptop, and a load test that dies because it
is not in CI is a load test people stop running locally.

## Stack

- [ ] **`spec-0007-markdown`** — the module, its wiring, its dependency test,
      and `RunResult.markdown()` against a golden.
      Done when: the golden matches, and the behind-schedule line is present
      only when the run was behind.
- [ ] **`spec-0007-step-summary`** — `appendToStepSummary()`, reading the
      environment variable, appending rather than truncating.
      Done when: with the variable pointed at a temporary file the summary is
      appended, and with it unset nothing is written and the return value says
      so.
- [ ] **`spec-0007-pages`** — the Pages directory layout and the `index.html`
      that links the reports in it, plus the workflow snippet in the docs.
      Done when: writing two runs into one directory produces an index listing
      both, newest first.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

Answered by the architect:

1. **The environment is read through an injectable lookup** defaulting to
    `System.getenv`, so the test does not mutate the JVM's environment.
2. **The markdown carries no colour and no emoji.** A table that renders the
    same in a terminal, a PR body and a job summary is worth more than a green
    tick that only renders in one of them.
3. **`index.html` is regenerated from the directory listing**, not appended to,
    so a deleted report cannot leave a dead link behind.
