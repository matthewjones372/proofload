# 0050 — One run at a time, whoever asked

## Problem

A load test that shares a machine measures the machine. 0041 measured it here:
a 40 ms target read 215 ms beside eight modules' tests. Nothing in the library
prevents it — `Kestrel.run()` will start a run while another is already going,
and say nothing.

The first draft of this spec hung the fix on `@LoadTest`. That ties isolation to
JUnit: Kotest's entry is a suspend function with no annotation to carry it, a
`main` has none either, and an annotation cannot see past its own JVM. Gradle's
`maxParallelForks` and its parallel test tasks are *other processes*, which is
the level 0041 was actually bitten by — so the annotation would have left the
real case unsolved while every user still wrote build configuration.

`Kestrel.run(Simulation)` is one choke point and every route reaches it: both
framework modules already declare `api(project(":kestrel-engine"))`, and
`run(Search)` fans out through the same call one rung at a time. Isolation
belongs there, where it is true for whoever asked and needs nothing from the
caller.

## Not doing

- No tag, no annotation, no Gradle recipe. That was the draft this replaces.
- No coordination across machines. One host, which is what contends for a CPU.
- No change to what a run measures, or to `examples`' `timing` tag and 0041's
  task-graph gate — that guards Kover instrumentation, a different thing.
- No queue with a policy. Runs wait in whatever order the OS grants them.

## Shape

The user writes what they already write, and two runs never overlap:

```kotlin
val result = kestrel.run(checkout.at(500.perSecond, over = 2.minutes))
```

In `kestrel-engine`, JDK only, so core's dependency test stays green:

```kotlin
object Exclusive {
    val state: State                                  // what this JVM is doing
    fun <T> whileAloneOnThisMachine(block: () -> T): T
}

sealed interface State {
    data object Idle : State
    data class Waiting(val since: Instant) : State    // another run has it
    data object Calibrating : State
    data object Running : State
    data object Draining : State                      // 0040's window
}
```

Two locks in that order, because they answer different questions: a
`ReentrantLock` serialises threads in this JVM and lets a capacity search hold
it across its rungs, then a `FileLock` on a path under the temp directory
serialises processes. A lock is taken **before the run's clock starts**, so a
wait is never inside a measurement.

## Why this shape

The file lock is what makes "no configuration" true rather than nearly true.
A JVM-scoped singleton alone solves only what `@ResourceLock` solved — two
tests in one fork — and leaves Gradle's other forks exactly as they were. The
OS releases a `FileLock` when the process dies, so a crashed run cannot wedge
the machine, which a lock file with a PID in it could.

The state machine is not decoration: it is what lets the wait be reported as a
measurement rather than disappear. `AGENTS.md` forbids parking a thread because
a wait becomes latency the tool blames on the target; naming `Waiting` as a
state distinct from `Running` is how that is enforced and checkable rather than
promised. It also makes entering `Running` without holding exclusivity an
illegal transition instead of a silent bug.

Where this could go another way: the engine could refuse rather than wait —
throw when another run holds the machine. That is simpler and worse, because
the common case is a build running two test tasks that would each have
succeeded a second apart.

## Stack

- [ ] **`spec-0050-exclusive`** — the singleton, the states, and the in-JVM
      lock around `run()` and `calibrate()`.
      Done when: two threads calling `run()` are shown never to overlap, a
      capacity search's rungs do not deadlock on their own lock, and
      `NoThirdPartyDependenciesTest` still passes.
- [ ] **`spec-0050-across-processes`** — the file lock, and the opt-out.
      Done when: two JVMs started together are shown to run one after the
      other, `-Dkestrel.exclusive=false` lets them overlap, and killing a
      holder frees the machine for the next.
- [ ] **`spec-0050-reported`** — the wait, where a reader sees it.
      Done when: a run that waited says how long, and a run that did not says
      nothing.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :examples:timingTests
```

## Open questions

1. **Where does the wait get reported?** A field on `RunResult` puts it on the
    page for free and grows core's shape; leaving it on `Exclusive` keeps core
    untouched and means only a caller who asks will see it. Recommend the
    field — a run that waited four minutes and does not say so is the kind of
    silence this repository keeps writing specs about.
2. **What is the lock file's path, and who else might hold it?** A fixed name
    under `java.io.tmpdir` is the obvious choice and is shared by every user on
    a multi-user box, which serialises strangers. Recommend it anyway, with the
    path overridable, since two strangers load-testing one host should queue.
3. **Does `benchmarks` opt out?** It measures the tool deliberately and may want
    several processes at once. Recommend it sets `kestrel.exclusive=false` in
    its own build file rather than the engine special-casing it.
4. **Should waiting have a ceiling?** An hour of queued runs looks identical to
    a deadlock. Recommend a timeout that fails loudly naming the holder, rather
    than waiting forever or proceeding unsafely.
5. **Does this make 0041's gate redundant?** No — that gate exists to keep a
    wall-clock task out of Kover's instrumentation, and it should stay. Worth
    saying so in `AGENTS.md` so the next reader does not remove one for the
    other.
