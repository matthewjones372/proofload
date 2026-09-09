# 0116 — a coverage number anyone can see

## Problem

`./gradlew build` enforces `minBound(80)` on line coverage and nothing says what
the number actually is. It is 92.6% today — twelve points of headroom nobody can
see, on a repository about to be public, where a reader deciding whether to trust
a load generator has the tests as their only evidence and no way to look at them
in aggregate.

Branch coverage is 73.4% over the same code, which is the more interesting number
and the one a single badge will hide.

## Not doing

- **No Codecov, no Coveralls.** A third party holding the number, a token to
  rotate and an outage that turns the README red. The number is already computed
  here.
- **Not a second measurement.** Kover runs in `check` already; this reads its
  report rather than producing another.
- **Not committing the badge to `main`.** A job that pushes to the branch that
  triggered it is a loop.
- **Not a branch-coverage floor.** Raising `minBound` is a separate argument.

## Shape

A task in the build, so the number can be read without a runner:

```bash
./gradlew coverageBadge && cat build/badges/coverage.json
{"schemaVersion":1,"label":"coverage","message":"92.6%","color":"brightgreen"}
```

CI publishes that file to an orphan `badges` branch, and the README points a
shields.io endpoint at it:

```markdown
[![coverage](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/matthewjones372/proofload/badges/coverage.json)](...)
```

## Why this shape

In the build rather than in YAML because the thresholds belong beside the floor
they relate to, and because a step nobody can run locally is a step that rots. The
task reads `koverXmlReport`'s own output, so the badge and the gate cannot disagree.

An orphan `badges` branch rather than a path on `main`: a job that commits to the
branch it was triggered by either loops or needs a `[skip ci]` convention that one
future edit will forget.

shields.io renders it from a raw URL, which means **this shows nothing until the
repository is public** — raw.githubusercontent.com will not serve a private file to
an anonymous badge service. The generation and the job are worth landing first so
that going public is one README line rather than a project.

## Stack

- [ ] **`spec-0116-task`** — `coverageBadge` reading the Kover report and writing
      the endpoint JSON.
      Done when: the message matches the report's LINE counter to one decimal, and
      the colour changes either side of the 80 floor.
- [ ] **`spec-0116-publish`** — a job on pushes to `main` only, `contents: write`
      and no secrets, publishing to an orphan `badges` branch.
      Done when: `badges` carries `coverage.json` and a pull request does not
      update it.
- [ ] **`spec-0116-readme`** — the badge itself, beside the build one.
      Done when: it renders — which is after the repository is public, not before.

## Acceptance

```bash
./gradlew coverageBadge
cat build/badges/coverage.json
```

## Open questions

1. **Which counter does the badge show?** Line is what the floor is on, and it
   flatters: 92.6% against 73.4% branches. Recommended: label it `coverage` and
   show line, because that is what the gate means — but say the branch number in
   `AGENTS.md` so it is written down somewhere.
2. **Two badges instead?** `coverage` and `branches` side by side is honest and
   makes the weaker number as visible as the strong one. Recommended: yes,
   eventually; one first.
3. **Does the badge job run on every push to `main`?** Recommended: yes. It is one
   Gradle invocation on an already-warm cache, and a stale badge is worse than none.
