# 0094 — Kestrel from Java

## Problem

Kestrel is JVM bytecode and a Java caller can technically reach all of it. What
it reaches is not an API. `kestrel-core`'s dump holds **245 public functions
whose names carry a value-class hash** — `p99-_FgASpo`, `goodput-_E9U6aE`,
`copy-8Mi8wO0` — and `kestrel-junit5` puts one on the assertion a test is meant
to end with, `assertNotWorseThan-tz0I1MY`. Three `@JvmInline` classes erase at
the boundary, so `50.perSecond` arrives as a bare `double` and `StepName` as a
bare `String`; every duration arrives as a `long` whose unit the type no longer
states; and `sessionKey<T>` is `reified inline`, so it cannot be called from
Java at all.

There are no `.java` sources anywhere in the tree and no test that compiles
one. Whatever works from Java today works by accident and can stop working in
any commit, because nothing would notice.

The people this matters to are not hypothetical: a load test is usually written
by whoever owns the service, and a Java team is not going to adopt Kotlin to
get one.

## Not doing

- **No `@JvmName` campaign in core.** Naming 245 functions twice makes the
  `.api` dump unreadable and puts the Java surface in the file the Kotlin
  reviewer reads. The seam belongs in a module.
- **No second scenario model.** The facade builds core's own values and
  computes nothing. A Java `Scenario` that is not a `Scenario` is a second
  source of truth for the thing this repository is about.
- **No wrapper types over the result.** `RunResult` stays what both languages
  read; Java gets accessors, not a parallel tree.
- **No Java 8.** The floor stays 21 — virtual threads are the engine.
- **No Scala.** [0095](0095-kestrel-from-scala.md).
- **No Java DSL sugar beyond what Kotlin has.** If a call reads worse in Java,
  that is a fact about Java.

## Shape

`kestrel-java`, over `kestrel-core` and `kestrel-engine`, everything static:

```java
import io.github.matthewjones372.kestrel.java.Kestrel;
import io.github.matthewjones372.kestrel.java.Rates;
import io.github.matthewjones372.kestrel.java.Scenarios;
import io.github.matthewjones372.kestrel.java.SessionKeys;
import io.github.matthewjones372.kestrel.java.Steps;
import java.time.Duration;

var orderId    = SessionKeys.of(String.class, "orderId");
var placeOrder = Steps.named("place order");
var api        = Https.baseUrl("https://orders.internal");

var checkout = Scenarios.named("checkout")
    .exec(Steps.named("browse"), api.get("/products"))
    .exec(placeOrder, api.post("/orders").body("{\"cart\":\"1 anvil\"}")
        .expecting(201)
        .capture(orderId, response -> response.header("location")))
    .build();

var result = Kestrel.create()
    .run(checkout.at(Rates.perSecond(50), Duration.ofMinutes(1)));

assertThat(Results.responseTime(result, placeOrder).p99())
    .isLessThan(Duration.ofMillis(200));
```

Three rules the module holds to: `java.time.Duration` in every signature and
out of every accessor; a builder where Kotlin has a lambda with a receiver; and
one static factory per value class, so no Java caller ever types a hash.

## Why this shape

A facade that delegates is the only version of this that cannot drift into a
second product. Every method is one line — build core's value, or read core's
field and convert the duration — which also means the review question is always
the same one, and a missing method is a gap rather than a bug.

The alternative is annotating core with `@JvmName` and `@JvmStatic` and calling
it done. It is less code and it is recommended against: the mangling comes from
value classes, which `@JvmName` cannot remove from a *parameter* type, so the
awkward half survives and core has paid for it anyway.

The gate is a compiled sample rather than an `.api` dump. `apiCheck` sees the
Kotlin surface and cannot see whether that surface is *callable* from Java —
only a Java compiler knows. `smoke` is the precedent: a source set whose job is
to fail when something published stops working.

## Stack

- [ ] **`spec-0094-module`** — `kestrel-java`, its dependency test, `Rates`,
      `Steps`, `SessionKeys` and the duration conversions.
      Done when: a Java source file constructs a rate, a step and a typed
      session key with no hash in it and no Kotlin import.
- [ ] **`spec-0094-scenarios`** — `Scenarios`, the builder, and `exec` over an
      action or a body.
      Done when: the scenario a Java builder produces equals the one the Kotlin
      DSL produces for the same steps.
- [ ] **`spec-0094-running`** — `Kestrel.create()`, `at`, and `Results`
      accessors returning `java.time.Duration`.
      Done when: a Java caller runs a scenario against a JDK `HttpServer` and
      reads a p99 without touching a mangled name.
- [ ] **`spec-0094-gate`** — a Java source set compiled by `build`, with the
      example above in it, plus `kestrel-java` added to `smoke`.
      Done when: deleting a facade method fails the build in the Java source
      set rather than in a consumer's project.
- [ ] **`spec-0094-docs`** — `docs/from-java.md`, and the Java column in
      `docs/modules.md`.
      Done when: every snippet on the page is a line from the compiled source
      set rather than prose typed beside it.

## Acceptance

```bash
./gradlew build
./gradlew :examples-java:compileJava
```

## Open questions

> Answered 2026-09-04, each on the recommendation in its own bullet. The
> reasoning is left standing rather than deleted: a decision is easier to
> reopen when the alternative it beat is still written down.

- **Does the facade cover the whole surface or the path a load test walks?**
  Recommend the path: scenarios, HTTP steps, running, goals, and reading a
  result. Capacity search, baselines, sharding and the exports are reachable as
  statics later, and a facade that must stay exhaustive is a facade that falls
  behind and lies about it.
- **Where do the Java sources live?** Recommend a new `examples-java` module
  rather than a source set inside `examples`: the Kotlin examples are compiled
  with different conventions and mixing the two makes both build files harder
  to read than a second small one.
- **Does `kestrel-junit5` get a Java-friendly assertion?** Recommend yes, in
  this module rather than that one — `Assertions.assertNotWorseThan(...)` —
  because the mangled name is on the call a Java test is supposed to end with.
