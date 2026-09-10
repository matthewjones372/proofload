# 0136 — A feeder from Scala

## Problem

0015 gave every virtual user its own data, as a function of the user's number.
None of it is reachable from Java or Scala: `feed`, `feedFrom`, `fedBy` and
`Feeder` appear nowhere in `proofload-java`, so the facade has no entry point
and there is nothing for `proofload-scala` to sit on.

The effect is not cosmetic. A Scala caller can only send load that is identical
for every user, which the cookbook itself names as the thing that measures the
target's cache rather than the target:

> Ten thousand users sending one identical order measure whatever the target
> does with a duplicate.

A real case: a load test against a Star Wars API wanted `GET /people/{a}/path-to/{b}`
with a different character pair per user, because the graph search is the whole
point of that endpoint and one pair measures one path. It could not be written,
so the test was dropped and the endpoint went unmeasured.

The Kotlin surface is small — a `SessionKey<T>`, a function of `Int`, and a
combining `+` — which is what makes its absence from the facade an oversight
rather than a decision. 0095 does not mention feeders; neither does 0094.

## Not doing

- **No new feeder kinds.** `feed`, `feedFrom` and the CSV reader are what 0015
  and 0054 settled; this exposes them and adds nothing.
- **No `Feeder` construction from Java lambdas returning boxed nulls.** A feeder
  that yields nothing for a user is a scenario bug, and stays one.
- **No cursor semantics.** A feeder is a pure function of the user number (0015),
  and the facade must not offer anything that looks like a cursor.
- Nothing about plan-file feeders. `proofload-plan` already lowers them.

## Shape

```java
public final class Feeders {
    public static <T> Feeder of(SessionKey<T> key, IntFunction<T> value);
    public static <T> Feeder fromList(SessionKey<T> key, List<T> values);
    public static Feeder combined(Feeder first, Feeder second);
    public static Simulation fedBy(Simulation simulation, Feeder feeder);
    public static Search fedBy(Search search, Feeder feeder);
}
```

```scala
val personId = sessionKey[String]("personId")
val target   = sessionKey[String]("target")

pathTo
  .at(50.perSecond, over = 1.minute)
  .fedBy(feed(personId)(user => (user % 82 + 1).toString) + feed(target)(user => (user % 61 + 7).toString))
```

`feed` takes the function in a second parameter list so the lambda reads as a
block, and `+` is the Scala name for `combined` because that is what the Kotlin
DSL already calls it.

## Why this shape

`IntFunction<T>` rather than `Function<Integer, T>` keeps the user number
unboxed on a path called once per user per key. The alternative — expose
`Feeder` as a Java functional interface and let callers implement it directly —
is recommended against: it puts the shape of the session map in the public API,
where today only `SessionKey` is.

`fedBy` overloaded on `Simulation` and `Search` rather than a single generic,
because those are the only two things 0031 lets a feeder attach to and a
generic would suggest otherwise.

## Stack

- [x] **`spec-0136-feeders`** — `Feeders`, its dependency test, and the `fedBy`
      overloads.
      Done when: a Java load test sends a different id per user.
- [x] **`spec-0136-scala`** — `feed`, `feedFrom`, `+` and `fedBy` over the facade.
      Done when: the scenario in Shape compiles in `examples-scala` and each
      user's request carries its own pair.
- [x] **`spec-0136-docs`** — the per-user-data section of `docs/from-scala.md`,
      quoted from the compiled sample.
      Done when: the page's feeder lines are lines of a source a compiler reads.

## Acceptance

```bash
./gradlew build
./gradlew :examples-scala:test
```

## Open questions

- **Should `feed` be curried, as sketched, or take both arguments at once?**
  Recommend curried: `feed(key) { user => ... }` is what the Kotlin reads like,
  and Scala 3 needs the second list to get the same braces.
- **Does the CSV feeder (0054) belong in this spec or the next one?** Recommend
  this one if it is a one-liner over the same facade, its own spec if it needs
  the file-reading contract restated.
- **`+` on `Feeder`, or `and`?** Recommend `+`, matching Kotlin; `and` reads
  better in a sentence but nobody writing this is writing a sentence.
