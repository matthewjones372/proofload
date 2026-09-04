# 0090 — The docs a model needs

## Problem

Kestrel's DSL is in nobody's weights. A model asked to write a Kestrel scenario
writes Gatling or k6 with Kotlin syntax: a base class to extend, string step
keys, `check(status is 200)`, a `Simulation` with a `setUp`. Every one of those
is a thing the README opens by saying Kestrel does not have, and the caller
finds out through a compile error it cannot map back to the right shape.

The material to fix it is all in the tree and none of it is in one place. The
README teaches the idea, `docs/cookbook.md` teaches the recipes, `docs/modules.
md` says which coordinates carry what, and the checked-in `.api` dumps from
0085 are the exact public surface — but a caller with one shot at a context
window has to be handed the union, not a table of contents.

## Not doing

- **Not a rewrite.** The README, the cookbook and `docs/` stay as they are and
  stay the source. This file is derived and shorter, not better.
- **Not generated docs.** No Dokka site, no API browser.
- **Not a prompt.** It states what the library is and how it is called; it does
  not tell a model how to behave.
- **No second set of examples.** Every snippet is one the repository already
  tests.

## Shape

`llms.txt` at the repository root, one screenful under a hundred lines, in
Pelican's format — which already exists next to this repository and is worth
matching rather than inventing:

```
# Kestrel

> Load testing for Kotlin. A scenario is a plain Kotlin value; the run reports
> the latency measured from when each request was *meant* to depart, and every
> run states whether the generator kept its own schedule.

Facts worth having before anything else:

- Kotlin 2.4, JVM 21+. Virtual threads on the JDK's own HTTP client.
- Coordinates: io.github.matthewjones372:kestrel-http, -junit5, -report-html.
- There is no base class, no string step key, and no XML.

## The shape of a test
...
## What it will not do
- No `Thread.sleep` anywhere: pacing is scheduled, not parked.
- A step name is a `Step` handle, not a string. Renaming it breaks the build.
```

Plus `docs/for-agents.md`, longer, with a **generated** API section: the `.api`
dumps rendered to signatures, so the surface in the docs cannot drift from the
surface `apiCheck` gates. And a "written wrong / written right" table of the
four mistakes above, which is the part that actually changes the output.

## Why this shape

The generated half is the point. A hand-written API summary is a second source
of truth for exactly the thing 0085 built a gate to stop having two of, and it
goes stale the first release nobody re-reads it. Rendering the dumps means a
removed method disappears from the docs in the commit that removed it.

The mistakes table earns its space because a model's failure here is not
missing knowledge, it is confident wrong knowledge carried in from a more
popular tool. Listing the right call is weaker than listing the wrong one
beside it.

`llms.txt` at the root rather than under `docs/` is the convention, and the
convention is the whole value — a caller looks in one place across repositories
or it looks nowhere.

## Stack

- [ ] **`spec-0090-llms-txt`** — the root file, hand-written, under 100 lines.
      Done when: a test asserts every coordinate it names resolves and every
      snippet it holds appears verbatim in a tested source file.
- [ ] **`spec-0090-api-render`** — a Gradle task rendering the `.api` dumps to
      the signature section of `docs/for-agents.md`.
      Done when: the task is wired into `check` and a stale section fails the
      build the way a stale `.api` dump does.
- [ ] **`spec-0090-mistakes`** — the written-wrong/written-right table.
      Done when: each "right" column compiles in `examples`.

## Acceptance

```bash
./gradlew build          # includes the drift check
wc -l llms.txt           # under 100
```

## Open questions

> Answered 2026-09-04, each on the recommendation in its own bullet. The
> reasoning is left standing rather than deleted: a decision is easier to
> reopen when the alternative it beat is still written down.

- **Does `for-agents.md` duplicate the cookbook?** Recommend not: link the
  cookbook and carry only the API surface and the mistakes. A second copy of
  the recipes is a second thing to keep true.
- **Should the mistakes table name Gatling and k6?** Recommend yes. The
  confusion is specific and naming it is what makes the row land; the tone stays
  factual rather than comparative.
- **Is this checked by a test or by a human?** Recommend a test for the drift
  and the snippets, and nothing for the prose — a golden over English is a
  golden nobody dares move.
