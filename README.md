# Kestrel

**Load testing for Kotlin, without the ceremony.** A scenario is an ordinary
Kotlin value: build it, inspect it, split it across files, run it.

> [!NOTE]
> Early. The description model is built and tested; nothing runs it yet. The
> engine is the next spec in [`specs/`](specs/). See [AGENTS.md](AGENTS.md)
> before writing code.

```kotlin
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.sessionKey
import kotlin.time.Duration.Companion.minutes

val cart = sessionKey<String>("cart")

val checkout = scenario("checkout") {
    exec("browse") { set(cart, "empty") }
    exec("add to cart") { set(cart, "1 anvil") }
    exec("pay") { if (get(cart) == "empty") fail("nothing to pay for") }
}

val simulation = checkout.at(50.perSecond, over = 1.minutes)
```

No session parameter to name, no result to remember to return, and no cast to
read one back: a key carries its type. `at` is Gatling's `setUp`, `inject` and
`protocols` in one call, and what it returns is an ordinary value —
`simulation.profile.userCount()` is 3000 before anything has been sent.

## What this is for

Gatling is the reference point and the thing to be simpler than. Its scenario
DSL is Scala, its reports are a bundled web app, and running one in CI means
adopting its plugin and its conventions. The bet here is that most teams want
a much smaller slice: describe a scenario in Kotlin, run it from a test or a
`main`, get numbers that are honest about what they measured.

The three decisions that shape everything else, and are still open:

- **What a scenario is.** A value, not a builder that runs as it is called —
  so it can be composed, filtered, printed and compared before anything is
  sent.
- **What runs it.** Whatever engine the first spec argues for, behind an
  interface core declares, so the description does not belong to it.
- **What honest numbers mean here.** Coordinated omission is the default bug
  in this class of tool; where the design admits it, it gets said out loud
  rather than smoothed over.

## Building

```bash
./gradlew build          # tests, detekt, spotless, coverage
./gradlew spotlessApply  # and run this last, before you commit
```

## Layout

| Module | Depends on | For |
|---|---|---|
| `kestrel-core` | **nothing** | scenarios as values |

Planned beside it, each a leaf with its own dependency test: `kestrel-engine`
(virtual threads), `kestrel-http`, `kestrel-report-html`,
`kestrel-report-github`, `kestrel-junit5`, `kestrel-kotest`, and
`kestrel-pelican` for [Pelican](https://github.com/matthewjones372/pelican)
endpoint descriptions.

Core depends on the Kotlin standard library and nothing else, and a test says
so. Everything with a third-party type in it becomes a leaf module beside it.

## License

Apache 2.0 — see [LICENSE](LICENSE).
