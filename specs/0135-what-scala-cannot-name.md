# 0135 — What Scala cannot name

## Problem

A value class in a parameter or return position mangles the JVM name, and a
Scala caller cannot type the result. `Results` exists because of this and solves
it for percentiles. The same wall stands in front of four more things, and
nobody has walked into them until now:

```
sustainable-B6_FF2g(Scenario, double, long, List<Goal>)   // Rate, Duration
warmingUp-HG0u8IE(Search, long)                            // Duration
Offered.getAsked-JlLZVlU()   Offered.getLeft-JlLZVlU()     // Rate
Rung.getRate-_3Rlgkc()       Capacity.getRate-Uz8bWpM()    // Rate
Difference.notWorseThan(…)                                 // Share, per 0108
```

So from Scala there is no capacity search, no baseline comparison, and no way to
read what load actually left. The consequence is not that these read awkwardly:
a Scala user writes their own. A load test on `0.1.0-rc1` hand-rolled a rate
ladder because `sustainable` could not be called, then read `Offered.share` — the
one clean getter — and multiplied it back out to recover `left`.

`judgedBy`, `Capacity.curve`, `.voided`, `.limitedBy`, `Rung.result`,
`.verdicts` and `.outcome` are already clean. The gap is narrow and mechanical.

## Not doing

- **No change to the Kotlin API.** Nothing here alters a signature Kotlin calls;
  every entry is a new static beside the existing one.
- **No general de-mangling pass.** Four types, named above, driven by what a
  caller actually hit. `Share`, `Rate` and `Duration` stay value classes.
- **No Scala-only fix.** The facade is Java, as `Results` is, so there is one
  translation rather than two.
- No baseline *format* work. 0021's comparison stands; only its front door moves.

## Shape

A Java facade per unreachable type, then a Scala extension over it:

```java
public final class Searches {
    public static Search sustainable(Scenario s, Rate upTo, Duration holding, List<Goal> expecting);
    public static Search warmingUp(Search search, Duration over);
    public static Rate rate(Capacity capacity);
    public static Rate rate(Rung rung);
    public static Rate offered(Rung rung);
}

public final class Offereds {
    public static Rate asked(Offered offered);
    public static Rate left(Offered offered);
}
```

```scala
val capacity = checkout.sustainable(upTo = 500.perSecond, holding = 2.minutes, expecting = goals).run()
capacity.rate       // FiniteDuration-free: a Rate the caller can print
result.offered.map(_.left)
```

## Why this shape

A facade per type rather than one `Facades` class, because that is what `Results`
established. The alternative — dropping `@JvmInline` from `Rate` — is recommended
against: it fixes every call site at once and costs an allocation on the hottest
path in the tool, which is the trade 0001 already refused.

## Stack

- [x] **`spec-0135-searches`** — `Searches`, its dependency test, and the Scala
      `sustainable`/`warmingUp` extensions.
      Done when: `examples-scala` runs a capacity search and reads its curve.
- [x] **`spec-0135-offered`** — `Offereds` and the Scala `offered` extension.
      Done when: a Scala caller reads `asked` and `left` without arithmetic.
- [ ] **`spec-0135-baselines`** — the Java-facing baselines assertion 0108's
      "Found while building" deferred, and `notWorseThan` from Scala.
      Done when: a zio-test spec compares a run against a stored baseline.

## Acceptance

```bash
./gradlew build
./gradlew :examples-scala:test
```

## Open questions

- **Does `Searches` belong in `proofload-java`, which does not depend on the
  search's `run`?** Recommend yes: the facade names types from core, and running
  is the caller's business either way.
- **Should the Scala `sustainable` take a `List[Goal]` or a vararg?**
  Recommend vararg, matching `scenario(...)`; the Java facade keeps the list.
- **Is `Rate` worth a Scala value type of its own, so `capacity.rate` prints as
  "500/s"?** Recommend not yet: `Rate.forReading()` already renders exactly that
  and is `private` in `Progress.kt`. Making it public is the cheaper move, and
  should be tried before a new type is invented.
