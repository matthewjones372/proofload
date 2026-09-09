# 0006 — A report you can open

## Problem

A `RunResult` is a value, which is right for asserting on and useless for
looking at. Gatling's answer is a bundled web application; the cost is that
seeing a number means running their tooling and adopting their layout.

## Not doing

- No server, no CLI, no watch mode. A function writes a file.
- No CDN, no npm, no build step, no framework. The page is hand-written.
- No history across runs, no comparison against a baseline. One run, one page.
- No CI wiring. `$GITHUB_STEP_SUMMARY` and Pages are spec 0007.

## Shape

```kotlin
import io.github.matthewjones372.proofload.report.writeHtmlReport
import java.nio.file.Path

val result = simulation.run()

result.writeHtmlReport(Path.of("build/reports/proofload/checkout.html"))
```

- `proofload-report-html`, depending on `proofload-core` and the JDK only, with its
  own `NoThirdPartyDependenciesTest`.
- One file. Data inlined as JSON in a `<script type="application/json">`, CSS
  and JS written by hand in the same file. It opens from a `file://` URL and
  uploads as a CI artifact unchanged.
- Above the fold: run start, total count, ok and failed, and **whether the
  generator kept its schedule**. A run that fell behind says so first, because
  every percentile under it means something else.
- Per step: count, ok, failed, service time and response time at p50/p95/p99
  and max, and the failure reasons with their counts.
- Interactive, in the small: sort the step table, click a step to expand its
  failure reasons, toggle service against response time.

## Why this shape

Self-contained is the whole point. A report that needs a server is a report
nobody opens from a CI artifact, and a report that pulls a chart library from a
CDN is a report that renders blank on a locked-down network in two years.

Hand-written CSS and JS in one file is more work than a framework and is the
only version that still opens in 2031. It also keeps the module honest: the
dependency test passes because there is genuinely nothing to depend on.

The precision the histogram states gets printed on the page. A percentile
rendered to three decimal places from a bucket 0.78% wide is a lie told by
formatting.

## Stack

- [ ] **`spec-0006-module`** — the module, its wiring, its dependency test, and
      the JSON encoding of a `RunResult` written by hand.
      Done when: a golden file holds the JSON for a hand-built result, and
      `./gradlew build` is green.
- [ ] **`spec-0006-page`** — the HTML shell, the summary block and the step
      table, as a golden file.
      Done when: the page for a hand-built result matches its golden byte for
      byte, and contains no `http://` or `https://` reference.
- [ ] **`spec-0006-interaction`** — sorting, expanding a step's reasons, and
      the service-against-response toggle.
      Done when: the JS is exercised by a test asserting on the rendered
      markup's hooks, and the behind-schedule banner appears only when the run
      was behind.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

Answered by the architect:

1. **Goldens are checked in and read in a diff.** A moved golden is the test
    working; regenerating for green is the failure mode AGENTS.md names.
2. **No charts in this spec.** A table that is correct beats a chart that is
    approximate. A histogram plot is worth its own spec, drawn as inline SVG
    from the bucket counts.
3. **The page prints the histogram's precision** beside the percentiles.
