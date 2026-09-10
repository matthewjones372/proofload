# 0139 — The Scala version a release promises

## Problem

`proofload-scala:0.1.0-rc3` cannot be used by most Scala projects. It depends on
`scala3-library_3:3.9.0`, and its own TASTy is 28.9:

```
Forward incompatible TASTy file has version 28.9,
produced by Scala 3.9.0-bin-nonbootstrapped,
expected stable TASTy from 28.0 to 28.8
```

Scala 3's TASTy is forward-incompatible, so any consumer below 3.9 gets that
error, and no dependency override fixes it — the version is in the library's own
bytecode. A project on 3.8.4 had to pin back to `rc1` to use the module at all.

This contradicts what the module documents about itself:

> Compiled against **Scala 3.3.8**, the LTS line. A published Scala library can
> only be read by a compiler at least as new as the one that built it, so this
> is deliberately not the newest release.

And it regressed between candidates, which is the part worth fixing properly:

| release | `scala3-library_3` |
|---|---|
| rc1 | 3.3.8 |
| rc3 | 3.9.0 |

Nothing caught it. The build compiles `examples-scala` with whatever the root
build declares, so a bump to the newest compiler is green locally and breaks
every consumer below it.

## Not doing

- **No cross-publishing.** 0095 recommended a single unsuffixed coordinate while
  there is one supported Scala version, and that still holds. This spec keeps one
  version; it does not add a matrix.
- **No Scala 2.13.** 0095 refused it and nothing has changed.
- **No change to the Kotlin or Java modules.** They are unaffected.
- No retraction of rc3. It stays published and superseded.

## Shape

The Scala version is declared once, named as a promise, and checked:

```kotlin
// build.gradle.kts
val scalaLts = "3.3.8"   // the floor a consumer's compiler must clear
```

A test that fails the build when the published TASTy would outrun it:

```
proofload-scala/src/test/.../TastyVersionTest
  reads the major/minor from a compiled .tasty in this module's own output
  and fails when it is above the version scalaLts produces
```

## Why this shape

Reading the TASTy header of this module's own output is the only check that
tests the thing consumers actually hit. The alternatives are both weaker: asserting
the *declared* `scalaVersion` catches a deliberate bump but not a toolchain that
resolves differently, and asserting the POM's `scala3-library` version catches
the dependency but not the bytecode. Recommend the header check, and it is a
dozen lines — the header is a magic number then two `writeNat` bytes.

Pinning to the LTS line rather than tracking the newest release is the trade
0095 already made, for the reason quoted above. The cost is that Proofload's
Scala module cannot use a 3.9 language feature. It uses none today.

## Stack

- [x] **`spec-0139-pin`** — `scalaLts` declared once, `proofload-scala` and
      `examples-scala` built against it, and an rc4 that a 3.3 consumer can read.
      Done when: `proofload-scala`'s POM names `scala3-library_3:3.3.8` again.
- [x] **`spec-0139-gate`** — the TASTy header test.
      Done when: raising `scalaLts` to a version above the floor fails
      `./gradlew build` in this module rather than in a consumer's project.
- [x] **`spec-0139-docs`** — `docs/from-scala.md` states the floor and where it
      is declared, so the page and the build cannot disagree.
      Done when: the page names the same constant the build reads.

## Acceptance

```bash
./gradlew build
./gradlew :proofload-scala:test
```

Then, from a project on Scala 3.3:

```bash
sbt "loadTest/compile"
```

## Open questions

- **Which LTS — 3.3.8 as documented, or the newest 3.3.x at release time?**
  Recommend the newest 3.3.x: the promise is the line, not the patch, and a
  patch bump inside LTS is TASTy-compatible.
- **Should the gate check the POM's `scala3-library` version as well as the
  TASTy header?** Recommend both if the second is also a dozen lines; they fail
  for different reasons and the POM one names the cause more clearly.
- **Does `smoke` cover this?** It resolves from `mavenCentral()` but builds with
  the same toolchain, so probably not. Recommend adding a Scala 3.3 consumer to
  `smoke` under `spec-0139-gate` if it is cheap, and its own spec if it is not.
