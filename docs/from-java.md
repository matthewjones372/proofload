# Proofload from Java

`proofload-java` lets Java code build and run the same scenarios the Kotlin DSL
builds. Calling `proofload-core` directly from Java is impractical: functions
that take `StepName`, `Share` or `Rate` (Kotlin inline value classes) have
mangled JVM names such as `p99-_FgASpo`, durations arrive as a bare `long`, and
`sessionKey<T>` compiles to no method at all.

The facade builds core's own values and computes nothing of its own: the
`Scenario` a Java builder produces is the `Scenario` the Kotlin DSL builds, and
the `RunResult` that comes back is the one both languages read.

## Taking it

```kotlin
// build.gradle.kts
dependencies {
    // Brings proofload-core, proofload-engine and proofload-http with it.
    implementation("io.github.matthewjones372:proofload-java:0.1.0-rc4")
}
```

## A load test

```java
import io.github.matthewjones372.proofload.RunResult;
import io.github.matthewjones372.proofload.Scenario;
import io.github.matthewjones372.proofload.SessionKey;
import io.github.matthewjones372.proofload.StepName;
import io.github.matthewjones372.proofload.Verdict;
import io.github.matthewjones372.proofload.http.Http;
import io.github.matthewjones372.proofload.java.Goals;
import io.github.matthewjones372.proofload.java.Https;
import io.github.matthewjones372.proofload.java.Proofload;
import io.github.matthewjones372.proofload.java.Rates;
import io.github.matthewjones372.proofload.java.Results;
import io.github.matthewjones372.proofload.java.Scenarios;
import io.github.matthewjones372.proofload.java.SessionKeys;
import io.github.matthewjones372.proofload.java.Simulations;
import io.github.matthewjones372.proofload.java.Steps;
import java.time.Duration;
```

```java
private static final SessionKey<String> ORDER_ID = SessionKeys.of(String.class, "orderId");
private static final StepName BROWSE = Steps.named("browse");
private static final StepName PLACE_ORDER = Steps.named("place order");
```

```java
Http api = Https.baseUrl("https://orders.internal");

Scenario checkout = Scenarios.named("checkout")
    .exec(BROWSE, api.get("/products"))
    .exec(PLACE_ORDER, Https.capturing(
        api.post("/orders").body("{\"cart\":\"1 anvil\"}").expecting(201),
        ORDER_ID,
        response -> response.header("location")))
    .pause(Duration.ofSeconds(1))
    .build();

RunResult result = Proofload.create().run(
    Simulations.at(checkout, Rates.perSecond(50), Duration.ofMinutes(1),
        Goals.p99Under(PLACE_ORDER, Duration.ofMillis(200)),
        Goals.failureRateUnder(0.1)));
```

Read what it measured through `Results`, which converts on the way out:

```java
Duration tail = Results.p99(result, PLACE_ORDER);
```

## Three rules

**`java.time.Duration` in every signature and out of every accessor.**
`kotlin.time.Duration` is a value class, so it crosses the boundary as a `long`
of nanoseconds and the type stops saying so.

**A builder where Kotlin has a lambda with a receiver.**
`Scenarios.named(...)` returns one; `build()` is the single place its steps are
frozen into core's `Scenario`.

**One static factory per value class**, so no Java caller ever types a hash:
`Rates.perSecond(50)`, `Rates.perMinute(30)`, `Steps.named("pay")`,
`Shares.percent(1)` and `SessionKeys.of(String.class, "orderId")`. These are
Java sources rather than Kotlin ones, and they have to be: a Kotlin function
returning a `@JvmInline` value compiles to a mangled name returning the `String`
or `double` underneath it, so no Kotlin signature can hand a `Rate` back to Java
as a `Rate` at all.

A step body is a `Consumer<StepScope>` through `Actions.of`, since a lambda with
a receiver is the one shape Java has nothing for. The rest of `Http` (`get`,
`post`, `body`, `header`, `expecting`, `timeout`) is already plain and is
called directly; only `capture` and `checking` are stated as a lambda in Kotlin
and so are named again on `Https`.

## Goals, and the verdicts they produce

Assert when one number decides the test. Declare goals when several do, or when
you want the report to say which one missed and by how much:

```java
RunResult result = Proofload.create().run(
    Simulations.at(checkout, Rates.perSecond(50), Duration.ofMinutes(1),
        Goals.p99Under(PLACE_ORDER, Duration.ofMillis(200)),
        Goals.failureRateUnder(0.1)));

for (Verdict verdict : Results.verdicts(result)) {
    System.out.println(verdict.getGoal().getDescribed() + (verdict.getMet() ? " met" : " missed"));
}
```

Kotlin writes the first of those as `p99(placeOrder) under 200.milliseconds`.
The infix form has no Java spelling, and every call that builds a goal takes a
`StepName` or a `Share`, so `Goals` is where they are named instead. The clock
defaults to response time in both languages, because a goal written against
service time can be met by a generator that never sent the load.

## What it covers

Scenarios with `exec` and `pause` steps, HTTP steps, running, goals, reading a
result, comparing against a baseline result with `Results.against`, and the
capacity search: `Searches.sustainable(checkout, upTo, holding,
goals)` builds one, `Proofload.create().run(search)` sends it, and
`Searches.rate` and `Searches.offered` read the rates its curve carries.
`Offereds.of(result)` is what a run asked for beside what actually left, which
is what a reader needs when the generator fell behind. `Feeders` gives each user
its own data, which the `{name}` in a path or a body template is filled from.

Not covered from Java: loops, conditions and `emit` in a scenario, other
protocols, reading and writing baseline files, sharding, and the exports. There
is no Java `assertNotWorseThan`, because it would put JUnit on the classpath of
every project that takes this module.

## The gate

[`examples-java`](../examples-java) contains the load test above, compiled by
`./gradlew build`, so a facade method that stops being callable from Java fails
the build. Every line of Java on this page is a line from that source, and
`FromJavaDocTest` fails when one stops being so.
