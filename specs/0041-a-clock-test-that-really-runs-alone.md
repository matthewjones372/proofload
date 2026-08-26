# 0041 — A clock test that really runs alone

## Problem

`AGENTS.md` says a test that measures elapsed time cannot share a machine, and
`examples/build.gradle.kts` implements that: `test` excludes the `timing` tag
and a separate `timingTests` task runs them alone with `maxParallelForks = 1`.
Both files say `./gradlew build` does not run them.

It does. Kover instruments every `Test` task in a project it aggregates, the
root project aggregates every module but `benchmarks`, and `check` depends on
`koverVerify` — so `:examples:timingTests` sits in the task graph of a plain
`./gradlew build`, alongside eight modules' tests:

```
$ ./gradlew build --dry-run | grep timingTests
:examples:timingTests SKIPPED
```

An agent building spec 0035 hit it three times in six runs, in both directions:
a 40 ms target measured 215 ms, then 32 ms; 78 ms, then 59 ms. It fails the same
way on an unmodified `main`, so it is not any one change. It is the exact
failure the build file's own comment describes, and it makes `./gradlew build`
— the one gate this repository tells every contributor and every agent to trust
— fail at random on a loaded machine. A gate that fails at random is a gate
people learn to re-run rather than read.

## Not doing

- No change to what the timing tests assert, or to the tags. The tests are
  right; what runs them is wrong.
- No dropping the coverage floor, and no removing `examples` from the
  aggregation. Its `test` task is real coverage of the modules composing.
- No retry-on-failure plugin. A flaky timing test that passes on the third
  attempt is still a machine measuring itself.
- No move to a dedicated CI runner. 0039 measures what a machine can resolve;
  this is about which tasks a build runs.

## Shape

```bash
./gradlew build --dry-run | grep timingTests   # no output
./gradlew :examples:timingTests                # still runs them, alone
```

The `timing` tag stays, the task stays, and its results stay out of the coverage
denominator — a wall-clock test of the engine covers the same lines the ordinary
tests already do, so counting it changes the floor by nothing anybody wants.

## Why this shape

Three ways to cut the edge. Excluding `timingTests` from Kover's instrumentation
in `examples` is the narrowest and leaves `check` alone. Dropping `examples`
from the root aggregation is one line and throws away the coverage the
composition tests earn. Making `koverVerify` depend only on the `test` task is
the most explicit and the most likely to break when a module adds a second test
task on purpose.

Recommend the first, with a test that asserts the task graph rather than a
comment claiming it. `AGENTS.md` already carries a table of gates and what they
fail on; a gate that quietly runs a task it says it does not is the kind of
thing only an executable check keeps honest.

## Stack

- [ ] **`spec-0041-alone`** — exclude `timingTests` from Kover instrumentation
      so `build` no longer schedules it, and keep it runnable on its own.
      Done when: `./gradlew build --dry-run` does not list
      `:examples:timingTests`, `./gradlew :examples:timingTests` still runs the
      tagged tests, and `koverVerify` still passes at the current floor with no
      change to the bound.
- [ ] **`spec-0041-asserted`** — a check that fails if a wall-clock test ever
      re-enters `build`, and the correction to `AGENTS.md`.
      Done when: the check fails on a tree where the exclusion is reverted, and
      `AGENTS.md`'s testing section describes what the build actually does.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew build --dry-run | grep -c timingTests   # 0
./gradlew :examples:timingTests
```

## Open questions

1. **Does the assertion belong in Gradle or in a test?** A Gradle task that
    inspects `check`'s dependency graph is precise and awkward to read; a test
    that shells out to `--dry-run` is slow. Recommend the Gradle side, failing
    configuration rather than execution, so a mistake is caught before anything
    runs.
2. **Should `benchmarks` be treated the same way?** It is already out of the
    aggregation by name. Recommend leaving it, and noting that the rule is now
    "measurement tasks are excluded" rather than "the benchmarks module is".
3. **Do the timing tests run in CI at all?** Today `build.yml` runs `build`, so
    they run by accident. Recommend a separate CI step that runs them alone
    after the build, rather than losing them entirely — a wall-clock claim
    nobody checks is a wall-clock claim that rots.
