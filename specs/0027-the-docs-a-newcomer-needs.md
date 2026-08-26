# 0027 — The docs a newcomer needs

## Problem

`AGENTS.md` says "every dependency claim in `docs/modules.md` is a test", and
`docs/modules.md` does not exist. The claim it refers to is real and tested —
every module asserts its own classpath — but the document a reader would go to
is missing, which makes the rule read like something aspirational.

More broadly, the only front door is a README with one example. Someone who
wants to know how to feed a scenario, shape a load curve, set goals, keep a
baseline, or wire this into a workflow has to read the specs, and specs argue
for changes rather than explaining how something works.

## Not doing

- No documentation site, no generator, no theme. Markdown in `docs/`.
- No tutorial series. One getting-started page, one cookbook, one reference for
  the module layout.
- No API reference by hand. Dokka already publishes the KDoc.
- No rewriting the specs into prose. They stay as the record of why.

## Shape

```
docs/
  modules.md        what each module is, what it depends on, and the test
  getting-started.md   from an empty project to a passing load test
  cookbook.md       feeders, shapes, goals, baselines, CI
  what-it-costs.md  (exists)
```

Every complete example carries its imports written out and the
`dependencies { }` block naming the modules it needs, with real coordinates —
`AGENTS.md` already requires this and nothing currently enforces it.

## Why this shape

`docs/modules.md` first, because a rule in `AGENTS.md` that points at a missing
file undermines every other rule in it.

The cookbook matters more than a tutorial. People arriving at a load-testing
tool have a specific question — how do I send a different id per user, how do I
ramp — and a page of recipes answers it in the time a tutorial spends on
introductions.

## Stack

- [ ] **`spec-0027-modules`** — `docs/modules.md`: the table, the rule, and a
      link to each module's dependency test.
      Done when: every module in `settings.gradle.kts` appears, and the file
      names the test that proves each claim.
- [ ] **`spec-0027-started`** — `docs/getting-started.md`, from an empty
      project to a green load test.
      Done when: the page's example compiles as written, with its imports and
      its coordinates.
- [ ] **`spec-0027-cookbook`** — `docs/cookbook.md`: feeders, shapes, goals,
      baselines, the workflow snippet.
      Done when: each recipe is a compiling example rather than a fragment.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

Answered by the architect:

1. **The examples in the docs are compiled**, by living in the `examples`
    module and being included by reference rather than pasted. A documentation
    example that does not compile is worse than no example.
2. **No badges beyond the ones the README already has.** A row of shields is
    not documentation.
3. **`docs/what-it-costs.md` stays as it is** and gets linked, not rewritten.
    It is the honest performance page and it reads as one.
