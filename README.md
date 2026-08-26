# Kestrel

**Load testing for Kotlin, without the ceremony.** A scenario is an ordinary
Kotlin value: build it, inspect it, split it across files, run it.

> [!NOTE]
> Nothing is built yet. This repository has its process, its build and its
> gates; the first spec in [`specs/`](specs/) is where the shape gets argued
> out. See [AGENTS.md](AGENTS.md) before writing code.

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

Core depends on the Kotlin standard library and nothing else, and a test says
so. Everything with a third-party type in it becomes a leaf module beside it.

## License

Apache 2.0 — see [LICENSE](LICENSE).
