# 0050 — A load test that runs alone by default

## Problem

`@LoadTest` is `@Test` plus `KestrelExtension` plus an hour's timeout. It
carries no tag and no isolation, so a user's load test runs inside their
ordinary `test` task, beside their unit tests, and two of them can run at once.

Everything keeping this repository's own wall-clock tests apart is build
configuration that does not ship: a hand-applied `@Tag("timing")` in `examples`,
a `timingTests` task at `maxParallelForks = 1`, a Kover exclusion, and the
task-graph gate in the root build. A user gets none of it, and nothing tells
them they need it. What they get is the failure 0041 measured here — a 40 ms
target reading 215 ms beside eight modules' tests — and they attribute it to
their service.

Interference arrives at three levels, and only the first is reachable from an
annotation: two tests in one JVM; two JVMs in one build, which JUnit cannot see
past its own fork; and the machine itself. 0041's failure was the second.
`RegressionTest`'s failures on 2026-08-31 — an unchanged target moving 2.4 ms
and 4.3 ms between repeats, against a floor of 8.2 µs — were the third.

## Not doing

- No Gradle plugin. A `kestrel-gradle` that registers the task in one line is
  the ergonomic end state and a spec of its own.
- No change to what any wall-clock test asserts, and none to `examples`' own
  `timing` tag, which is this repository's internal concept.
- No retry-on-failure. 0041 settled that.
- No isolation for Kotest's `kestrel()`. Named below as a question, not built.

## Shape

The user writes what they already write and gets isolation without asking:

```kotlin
@LoadTest
fun `checkout holds at 500 a second`(kestrel: Kestrel) {
    kestrel.run(checkout.at(500.perSecond, over = 2.minutes))
}
```

`@LoadTest` gains two meta-annotations, and a constant a build can name:

```kotlin
@Tag(LoadTests.TAG)               // "load": the hook a build filters on
@ResourceLock(Resources.GLOBAL)   // no two of these overlap in one JVM
annotation class LoadTest
```

with the part the annotation cannot supply written out in `docs/`:

```kotlin
tasks.test { useJUnitPlatform { excludeTags(LoadTests.TAG) } }

tasks.register<Test>("loadTests") {
    useJUnitPlatform { includeTags(LoadTests.TAG) }
    maxParallelForks = 1
}
```

## Why this shape

`@Isolated` is the obvious choice and cannot be used: it targets `TYPE` alone
and `@LoadTest` targets `FUNCTION`. `@ResourceLock` targets both, so it is the
only one that composes with a method-level annotation — checked against
junit-jupiter-api 6.1.3, which is what this module actually resolves.

The tag is the valuable half: it is the hook a build tool needs and the thing
no user will remember to add by hand, and it costs the annotation nothing.

The alternative is an annotation that promises nothing and documentation that
carries all of it. That keeps `@LoadTest` a pure alias and leaves every user to
find the problem the way this repository found it. Recommend the
meta-annotations — a default that is right is worth more than a paragraph that
is read.

## Stack

- [ ] **`spec-0050-annotation`** — `@Tag` and `@ResourceLock` on `@LoadTest`,
      and `LoadTests.TAG` for a build to name.
      Done when: two `@LoadTest` methods are shown not to overlap with parallel
      execution enabled, and `kestrel-junit5`'s own tests still run.
- [ ] **`spec-0050-recipe`** — the recipe in `docs/`, and `examples` adopting
      the shipped tag beside its own.
      Done when: `./gradlew :examples:loadTests` runs them alone, and
      `./gradlew build --dry-run` lists neither it nor `timingTests`.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew build --dry-run | grep -cE "loadTests|timingTests"   # 0
./gradlew :examples:timingTests
```

## Open questions

1. **The module is `kestrel-junit5` and depends on JUnit 6.1.3.** Recommend
    renaming to `kestrel-junit` before 0.1.0 is cut, since a published artifact
    name is permanent. Bigger than this spec; belongs beside 0029.
2. **`load` or `timing` for the tag?** Recommend `load`. `timing` is this
    repository's word for "measures elapsed time" and the root build's gate keys
    on it; a user's load test and this repo's clock test are not the same claim.
3. **Should `@LoadTest` refuse to run on a machine it cannot measure?** Level
    three cannot be prevented, only detected, and `calibrate()`, `Floor` and
    `RegressionTest`'s `assumeTrue` already do exactly that. Recommend yes, as
    its own spec: a test that skips itself unasked is its own argument.
4. **What does Kotest get?** There is no annotation there — `kestrel()` is a
    suspend function. Recommend a base spec carrying Kotest's `@Isolate`,
    decided separately rather than bent into this shape.
