# 0087 — A result a machine can read

## Problem

A run's judgement exists in one place a person reads and nowhere a program
does. `RunResultJson.toJson` is `internal` and shaped for the page it is
inlined into, so a caller — a CI step, a script, an agent driving the tool —
has three choices: scrape the HTML, re-derive percentiles off `RunResult` in
Kotlin, or read the OpenMetrics text from 0073, which carries the buckets and
deliberately leaves the judgement out.

The judgement is the thing worth reading. Whether the generator kept its
schedule, which goal missed and by how much, what the failures were, whether
the machine could even resolve the claim — 0020, 0039, 0068 and 0076 all
computed an answer that currently only reaches a browser.

## Not doing

- **No new measurement.** Every field here is already computed. This is a
  serialisation, and a field with no existing source does not go in.
- **No replacing the baseline format.** 0021's tab-separated lines stay what
  `Runs.readAll` reads. This does not round-trip.
- **Nothing during a run.** 0073's rule: a reader on the measured path is a
  serialisation pass the target gets billed for. This reads a frozen result.
- **No third-party JSON library.** `kestrel-export` is core and the JDK, and
  its `NoThirdPartyDependenciesTest` says so.
- **No schema for the buckets.** A histogram belongs in the hlog.

## Shape

Two densities on a frozen result, in `kestrel-export`:

```kotlin
import io.github.matthewjones372.kestrel.export.json
import io.github.matthewjones372.kestrel.export.writeJson
import java.nio.file.Path

val result = kestrel.run(checkout.at(50.perSecond, over = 1.minutes))

println(result.json(Density.Summary))          // the verdict, under 2 KB
result.writeJson(Path.of("build/run.json"))    // Density.Full, every step
```

`Summary` is the answer and nothing else: the plan asked for, the load that
left, whether the schedule held, one line per goal with its margin, failures
grouped by `Reason` type, the steady segment, the resolution floor, and Little's
law. `Full` adds per-step percentiles, the timeline and the stages.

Every verdict carries a **`remedy`**: the sentence saying what to do about it.

```json
{ "schema": "kestrel/run/1",
  "verdict": "behind",
  "remedy": "The generator fell behind at second 42; tail numbers are not the target's. Reduce to 38/s or add an injector." }
```

A `schema` string is on the document, and a JSON Schema lands in
`docs/schemas/run-1.json`.

## Why this shape

A caller reading a run is nearly always asking one question — did it pass, and
if not, is the answer even valid — and paying for a full per-step dump to learn
it is the reason people scrape the page instead. Two densities, with the small
one small enough to hold in a prompt or a comment, is what makes the summary the
default rather than the thing nobody uses.

`remedy` is prose in a data file, which is arguable. The alternative is a
structured cause — `{"cause":"behind","atSecond":42,"suggestedRate":38}` — and a
caller that writes its own sentence. Recommend both: the structured fields *and*
the sentence, because the fields keep it honest and the sentence is what a reader
acts on. The page already prints these sentences; this stops them being written
twice.

`kestrel-export` rather than `kestrel-report-html`: the judgement is not a
report format, and a caller wanting JSON should not pull in a stylesheet.
`RunResultJson` stays where it is and keeps serving the page — it is a different
document with a different job, and merging them would tie the page's shape to a
published schema.

## Stack

- [ ] **`spec-0087-writer`** — the JSON writer moved to `kestrel-export`,
      `Density`, and the document envelope with `schema`.
      Done when: `result.json(Summary)` round-trips through a golden and
      `kestrel-export`'s dependency test still passes.
- [ ] **`spec-0087-verdicts`** — goals, the schedule verdict, Little's law and
      the floor, each with its structured cause.
      Done when: a run that fell behind serialises `verdict: "behind"` with the
      second it went, and a run that met every goal serialises none.
- [ ] **`spec-0087-remedy`** — the sentence per verdict, taken from the strings
      the report already prints.
      Done when: one golden holds every remedy the codebase can produce, and the
      page and the JSON read from the same place.
- [ ] **`spec-0087-schema`** — `docs/schemas/run-1.json`, and a test validating
      both goldens against it.
      Done when: adding a field without touching the schema fails the build.

## Acceptance

```bash
./gradlew build
./gradlew :examples:runOnce && test $(wc -c < build/run-summary.json) -lt 2048
```

## Open questions

> Answered 2026-09-04, each on the recommendation in its own bullet. The
> reasoning is left standing rather than deleted: a decision is easier to
> reopen when the alternative it beat is still written down.

- **Does `Summary` include per-step p99, or only goals?** Recommend goals only:
  a step with no goal on it is a number nobody asked a question about, and
  `Full` is one flag away.
- **Is `schema` a version string or a URL?** Recommend the string
  `kestrel/run/1`, with the URL in the schema file — a URL in every document is
  a hostname to keep alive.
- **Does the CHANGELOG treat a new optional field as breaking?** Recommend no,
  and say so in the schema file: readers must ignore unknown fields.
