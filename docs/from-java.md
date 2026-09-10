# Proofload from Java

Proofload is JVM bytecode, so a Java caller can reach all of it. What it reaches
is not an API: `proofload-core` publishes 245 functions whose names carry a
value-class hash (`p99-_FgASpo`, `goodput-_E9U6aE`) because `StepName`,
`Share` and `Rate` are `@JvmInline`. Every duration arrives as a bare `long`
whose unit the type no longer states, and `sessionKey<T>` is `reified inline`,
which compiles to no method at all.

`proofload-java` is the seam. It builds core's own values and computes nothing:
the `Scenario` a Java builder hands the engine is the `Scenario` the Kotlin DSL
builds, and the `RunResult` that comes back is the one both languages read.

## Taking it

```kotlin
// build.gradle.kts
dependencies {
    // Brings proofload-core, proofload-engine and proofload-http with it.
    implementation("io.github.matthewjones372:proofload-java:$proofloadVersion")
}
```

## A load test

```java
import io.github.matthewjones372.proofload.RunResult;
import io.github.matthewjones372.proofload.Scenario;
import io.github.matthewjones372.proofload.SessionKey;
import io.github.matthewjones372.proofload.StepName;
import io.github.matthewjones372.proofload.http.Http;
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

## What it does not cover

The path a load test walks: scenarios, HTTP steps, running, goals, and reading
a result. Capacity search, baselines, sharding and the exports are reachable as
statics later. There is deliberately no Java `assertNotWorseThan`: `Difference`
is a baselines type, and wrapping it here would put JUnit on the classpath of
every project that takes this module, which is what `proofload-junit5` exists to
prevent. A facade that has to stay exhaustive is a facade that falls
behind and lies about it.

## The gate

[`examples-java`](../examples-java) is a module whose whole content is the load
test above, compiled by `./gradlew build`. `apiCheck` records the Kotlin surface
and cannot see whether that surface is *callable* from Java. Only a Java
compiler knows, so a facade method that goes fails the build there rather than
in your project. Every snippet on this page is a line from that source, and
`FromJavaDocTest` fails when it stops being one.
