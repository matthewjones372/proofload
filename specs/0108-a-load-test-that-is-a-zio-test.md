# 0108 — A load test that is a zio-test test

## Problem

[0095](0095-kestrel-from-scala.md) landed, so a Scala team can write a
scenario. It cannot write a *test*: [0008](0008-a-load-test-is-a-test.md) and
[0009](0009-kotest.md) gave JUnit 5 and Kotest a runner each, and a ZIO service
is tested in zio-test. Dropping to a JUnit class to write a load test is the
"adopt our runner" tax this project exists to avoid, and here it is worse than
for a Kotlin team: a zio-test suite is an effect, so a blocking call in the
middle of one is not a style clash but a fiber parked on the compute pool.

0095's **Not doing** says no ZIO. That refusal is about the *engine* — Kestrel
handing back a `ZIO` for a step body, or departures scheduled on a fiber
runtime — and it stands. This spec is about the call around a whole run, which
is a different question with a different answer.

## Not doing

- **No `ZIO` inside a run.** A step body is an `Action` and stays one. Nothing
  here puts a fiber between the departure clock and the socket, which is the
  thing 0095 refused and the reason this tool can claim its own overhead.
- **No `ZLayer`, no service, no `ZIO.serviceWithZIO`.** A runner is a value a
  test makes, not an environment it must provide.
- **No `TestClock` support, and it is not an omission.** A run measures the
  wall clock. `TestClock.adjust` cannot fast-forward a one-minute run, and a
  version that let it would be reporting a number nothing measured.
- **No failure-summary aspect, no cats-effect, no Pekko.** The first is under
  Open questions; the other two are the same shape with different libraries,
  and nobody has asked.

## Shape

`kestrel-zio-test`, over `kestrel-scala`, with zio-test `compileOnly` as Kotest
is in `kestrel-kotest`:

```scala
import io.github.matthewjones372.kestrel.ziotest.kestrel
import zio.test.ZIOSpecDefault
import zio.test.assertTrue

object CheckoutSpec extends ZIOSpecDefault:

  def spec = suite("checkout")(
    test("holds p99 under 200ms at 50 a second"):
      for result <- kestrel.run(checkout.at(50.perSecond, over = 1.minute))
      yield assertTrue(result(pay).responseTime.p99 < 200.millis),
  )
```

`kestrel.run` is the whole module: `ZIO.attemptBlocking` around the same
`Kestrel(Progress.silent)` the other two framework modules build. Blocking
rather than compute, because the call blocks its thread for the length of the
run while the engine sends on virtual threads; on the compute pool that is a
starved runtime, and a starved runtime is a scheduler this tool would then
measure.

Silent for the reason the JUnit extension is silent: a zio-test report is
somebody else's output. Exclusive because `Kestrel()` already is, so
`TestAspect.parallel` over two load tests measures the machine twice in a row
rather than measuring each other.

## Why this shape

One method, and the argument is entirely about which executor it runs on. The
alternative — a `ZLayer[Any, Nothing, Kestrel]`, which is what a ZIO library
normally looks like — is recommended against: it buys nothing a `val` does not,
and makes the runner look like a resource with a lifetime when it is a value
with none. The dependency test is where "this changes no engine behaviour" gets
checked, as it was in 0009, and it matters more here than the surface does.

## Stack

- [x] **`spec-0108-module`** — `kestrel-zio-test`, its `NoSecondStackTest`, and
      `kestrel.run` on the blocking executor.
      Done when: a `ZIOSpecDefault` runs a simulation against a JDK
      `HttpServer` under `./gradlew build`, and a test names the executor the
      run happened on.
- [x] **`spec-0108-assertions`** — ~~`notWorseThan` as an
      `Assertion[Difference]`, and~~ `metItsGoals` over the run's own verdicts.
      Done when: a failing run names every goal that missed and the remedy each
      carries, rather than stopping at the first.
      The `Difference` half was struck out while building; see below.
- [ ] **`spec-0108-docs`** — the `docs/modules.md` rows, `smoke`, and a section
      on `docs/from-scala.md` quoted from the compiled spec.
      Done when: the page's zio-test lines are lines of a spec the build runs.

> Landed on the same branch as 0095, at the author's direction, so the entries
> above are commits rather than pull requests.

## Acceptance

```bash
./gradlew build
./gradlew :kestrel-zio-test:test
```

## Found while building

- **`notWorseThan` cannot be reached from Scala at all, and this is not the
  spec that should fix it.** `Difference.notWorseThan` and `Difference
  .explained` are top-level extensions taking a `Share`, so their JVM names
  carry a value-class hash — the thing `kestrel-java` exists to keep out of
  another language's source, and it has no `Differences` facade because
  [0094](0094-kestrel-from-java.md) reversed itself and put a Java-facing
  baselines assertion in a spec of its own. Adding one here would be that spec,
  written in the wrong place. So `metItsGoals` landed alone, and a zio-test
  spec comparing against a baseline waits for the baselines facade.
- **zio-test runs under Gradle through `zio-test-junit-engine`**, a JUnit
  platform engine rather than the JUnit 4 runner `zio-test-junit` carries. Test
  names come out as the sentences they were written as, which the Scala
  backtick names in `kestrel-scala` do not.

## Open questions

- **Does the module attach what the runs measured to a failure?** Recommend
  **not yet**: a `TestAspect` that annotates a failed test with
  `Kestrel.summary()` is maybe twenty lines, and 0009 specified the same thing
  for Kotest and never built it, which suggests the assertion's own message is
  usually enough. Worth doing for all three frameworks at once, or for none.
- **Is "on the blocking executor" the right test, or should it be a timing
  comparison?** Recommend the executor: asserting that a run started from a
  fiber departs on the same schedule as one started from a plain thread is a
  wall-clock test, and those cost an isolated task and a tag. The executor is
  the mechanism the schedule depends on, and it is checkable in microseconds.
- **`kestrel-zio-test` or `kestrel-zio`?** Recommend the longer name: this
  integrates a test framework, not an effect system, and the shorter name
  should stay free for the spec that turns 0095's refusal down again.
