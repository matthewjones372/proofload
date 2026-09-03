# 0085 — An API that is written down

## Problem

The CHANGELOG says it: the build has no binary compatibility check and the tree
has no `.api` dump, so a break between 0.1.0 and 0.2.0 is caught by that file
and by nothing else. Fourteen modules are published together
(`build.gradle.kts:136`) and every one of them is public surface.

This session alone made three breaks by hand: `Timing.precision` became
required, `Request.body` became a sealed `Body`, `StepSink.record` grew a
parameter. Each was noticed because the compiler in this repository found it.
None of them would have been noticed if the caller had been somebody else's
project, and the only reason each is in the CHANGELOG is that somebody
remembered to write it there.

"Remembered to" is the whole problem. A promise kept by memory is a promise
that holds until the first tired afternoon, and the thing it is protecting is
the one thing a library owes anyone.

Pre-1.0 the rule is that breaks come without a major bump and are recorded.
That rule is only worth anything if *recorded* is checked.

## Not doing

- **No freezing the API.** This records what the surface is; it does not
  promise not to change it. Until 1.0 a break is allowed, and the point is that
  it is deliberate and visible in a diff.
- **No semantic versioning enforcement.** A tool cannot tell a break that
  deserves a major bump from one that does not. It can tell a break.
- **No source compatibility.** Binary is what a published jar owes; source
  breaks that are binary-compatible (a default argument added, a parameter
  renamed) are the CHANGELOG's job and stay it.
- **No `@PublishedApi` archaeology.** What the tool considers public is what it
  considers public; anything this repository wants hidden gets `internal`,
  which it should have anyway.
- **Nothing new in a published jar.** The plugin adds a task and a file, not a
  dependency.

## Shape

```bash
./gradlew apiDump    # rewrite the .api files after a deliberate change
./gradlew apiCheck   # fails when the surface moved and the dump did not
```

```
kestrel-core/api/kestrel-core.api
kestrel-http/api/kestrel-http.api
...one per published module, checked in
```

- `org.jetbrains.kotlinx:binary-compatibility-validator`, applied to
  `publishedModules` and to nothing else — `examples` and `benchmarks` are not
  libraries and their surface is nobody's business.
- `apiCheck` runs from `check`, so `./gradlew build` fails on an unrecorded
  break exactly as it fails on a detekt finding. The gate in `AGENTS.md` does
  not change.
- The dump is reviewed like any other file. A pull request that removes a line
  from a `.api` file is a pull request that breaks somebody, and the diff says
  so in the one place a reviewer is already looking.

## Why this shape

**A file in the tree, not a comparison against a published jar.** Nothing is
published yet (`0029-tag`), so there is no baseline artifact to compare
against, and a check that only works after the first release is a check that
does not exist for the release that needs it most. A checked-in dump works from
the commit it lands on.

**`check`, not a separate task somebody runs.** A gate that has to be
remembered is the thing this spec is replacing. `AGENTS.md` names
`./gradlew build` and that must stay the whole gate.

**The dump is the review artifact.** The value is not the tool failing; it is
that `+ public final fun getVisits ()J` appears in a diff beside the change
that added it, so the CHANGELOG entry is written while the author is looking at
the reason for it rather than a week later.

**Applied per module, from the published list.** `publishedModules` already
exists and already means "the surface anybody depends on". Deriving from it
means a new module gets a dump the day it is added, without anybody
remembering.

## Stack

- [ ] **`spec-0085-validator`** — the plugin, applied to `publishedModules`,
      wired into `check`.
      Done when: `./gradlew apiDump` writes one file per published module,
      `./gradlew build` passes on the tree as it stands, and `examples` and
      `benchmarks` have no dump.
- [ ] **`spec-0085-proof`** — a test that the check actually catches a break.
      Done when: removing a public function and running `apiCheck` fails
      naming it, and the proof is written down rather than claimed — a script
      or a documented transcript, since a test that breaks its own module's API
      cannot live in that module.
- [ ] **`spec-0085-changelog`** — the limitation removed, and the process
      written where a contributor reads it.
      Done when: `AGENTS.md` says a public change means `apiDump` in the same
      commit, and the CHANGELOG's Limitations section no longer claims nothing
      records the API.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

Then delete a public function and watch `./gradlew build` fail naming it.

## Open questions

1. **Does the dump go in `api/` or beside the source?** The plugin defaults to
    `<module>/api/<module>.api`. Recommend the default: a contributor who has
    seen it in another Kotlin project finds it where they expect.
2. **What happens to the fourteen dumps on the first release?** They will be
    large and nobody will read them once. Recommend landing them in a commit of
    their own that changes nothing else, so the diff of the next change is
    readable.
3. **Should Kover's coverage gate and this share a task?** No, but both are
    gates that fire from `check`. Recommend leaving them separate and saying so
    in `AGENTS.md`, which lists what the gate runs.
4. **Does this want to arrive before or after `0029-tag`?** Before: the surface
    a release freezes should be recorded in the commit the release is cut from,
    not reconstructed after.
