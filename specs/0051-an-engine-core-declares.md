# 0051 — An engine core declares

## Problem

The README names three decisions that shape everything else. The second is:

> **What runs it.** Whatever engine the first spec argues for, behind an
> interface core declares, so the description does not belong to it.

Core declares no such interface. `Simulation.run()` is a top-level extension
function in `kestrel-engine`, so an engine is selected by which `run` is
imported; `Kestrel.run(simulation)` calls it and is therefore welded to virtual
threads; and `kestrel-junit5` and `kestrel-kotest` both hand out that `Kestrel`.
The description does belong to the engine, and the one sentence a reader would
check it against says otherwise.

Nobody has been hurt yet, because there is one engine. It gets expensive at the
moment there are two — an actor engine, a coroutine one, an event loop — and
0050 brings that moment forward: exclusivity is a property of the machine, and
putting it inside one engine means two engines in one process do not serialise
against each other, which is the bug 0050 exists to remove.

## Not doing

- No second engine. This is the seam, not a user of it. A seam with one
  implementation is still worth having when the alternative is a false claim.
- No change to what the virtual-thread engine does, measures or costs.
- No service-loader or classpath discovery. A constructor argument is enough
  and `AGENTS.md` has already ruled out registries.
- No suspend or reactive shape for the interface. Named below as a question.

## Shape

Core declares what a runner is, in terms of the values it already owns:

```kotlin
fun interface Engine {
    fun run(simulation: Simulation): RunResult
}
```

The virtual-thread module becomes one implementation rather than the only one,
and the runner takes one:

```kotlin
class VirtualThreads : Engine

val kestrel = Kestrel()                    // virtual threads, as today
val kestrel = Kestrel(engine = Actors())   // whatever else implements it
```

`Search` needs nothing new: it is already `judgedBy { rung -> rung.run() }`, so
a capacity search over any engine falls out of the one method.

## Why this shape

A `fun interface` with one method is the whole seam. Everything else an engine
appears to need — the search, the recorders, the plan — is either core's
already or derived from `run`, so a wider interface would be describing this
engine's internals rather than what a runner is.

The alternative is to leave the extension function and let a second engine
shadow it by import. That is less code and it puts the choice in whichever file
happens to import which name — a scenario would run on a different engine
depending on an import, which is exactly the coupling the README's sentence
says does not exist.

The seam is worth landing before 0050 rather than after: a machine-wide lock
placed inside one engine has to move the moment there is a second, and a lock
that moved is a lock somebody has to re-prove.

## Stack

- [ ] **`spec-0051-interface`** — `Engine` in core, `VirtualThreads`
      implementing it, `Kestrel` taking one and defaulting to it.
      Done when: `Kestrel(engine = ...)` runs a simulation through a test
      double that records it, the default still runs on virtual threads, and
      `NoThirdPartyDependenciesTest` still passes.
- [ ] **`spec-0051-chosen`** — the framework modules letting a caller name one.
      Done when: a JUnit load test and a Kotest spec each run on a supplied
      engine, and neither module depends on the other to do it.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :examples:timingTests
```

## Open questions

1. **Does `Kestrel` move to core?** It is the facade both framework modules
    hand out, and it currently lives in `kestrel-engine` beside the engine it
    defaults to. Recommend leaving it: a default has to name an implementation,
    and core naming one would be the coupling this spec removes. A consumer who
    wants only the actor engine can depend on core and that module.
2. **Is `calibrate()` the engine's or the machine's?** A floor is a property of
    the machine, but it is measured by running a null step, which needs an
    engine. Recommend it stay on `Kestrel` and run through whichever engine it
    holds, so a floor describes the machine as that engine will drive it.
3. **Does the interface need a suspend variant?** A coroutine or actor engine
    would have to block inside `run`. Recommend blocking for now — every caller
    today is a test method that blocks anyway — and revisit when a non-blocking
    engine actually exists rather than designing for one that does not.
4. **Does `Simulation.run()` survive?** Keeping it as sugar over the default
    engine preserves the README's examples and every doc snippet. Recommend
    keeping it, defined in terms of `VirtualThreads`, so the extension is a
    convenience rather than the mechanism.
