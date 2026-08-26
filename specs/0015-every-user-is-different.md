# 0015 — Every user is different

## Problem

Every virtual user sends the same bytes. The same id in the path, the same body,
the same query. A target with a cache in front of it answers the first request
and serves the rest from memory, and the run reports a service that never did
the work.

There is no way to say otherwise: a `Session` starts empty and the only things
in it are what earlier steps put there.

## Not doing

- No CSV, no JSON, no file formats. A file is a source of a list, and the list
  is the thing this spec is about — readers come after, in a leaf module if
  they need a parser.
- No database or queue sources.
- No sharing a feeder between two simulations, and no cursor anyone can
  observe.
- No random by default. A run that cannot be repeated is a run nobody can
  bisect.

## Shape

```kotlin
import io.github.matthewjones372.kestrel.fedBy
import io.github.matthewjones372.kestrel.feed
import io.github.matthewjones372.kestrel.feedFrom
import io.github.matthewjones372.kestrel.sessionKey

val email = sessionKey<String>("email")
val region = sessionKey<String>("region")

val users = feed(email) { user -> "user$user@example.com" } +
    feedFrom(region, listOf("eu-west", "us-east", "ap-south"))

val simulation = checkout.at(50.perSecond, over = 1.minutes).fedBy(users)
```

- `Feeder` — a function from a user's index to the values that user starts
  with. A value, so two feeders combine with `+` and a simulation carrying one
  can still be inspected before it runs.
- `feed(key) { index -> value }` — computed per user.
- `feedFrom(key, values)` — the list, indexed by the user's number, wrapping
  round when there are more users than values.
- `Simulation.fedBy(feeder)` — what the engine seeds each session from.

## Why this shape

A function of the user's index rather than a cursor over a source. A cursor is
shared state on the hot path: every departure would contend on it, and a load
generator that takes a lock to decide what to send is measuring itself again.
An index is already in hand — the engine counts departures — so there is
nothing to synchronise and nothing to run out of.

It also makes a run repeatable. User 4,001 gets the same email on Tuesday as it
did on Monday, so a failure that mentions a row can be looked at rather than
reproduced by luck. Random data is still available by seeding from the index;
what is not available is randomness nobody can reproduce.

Wrapping round rather than exhausting: a feeder that runs out mid-run would end
a load test for a reason that has nothing to do with the target.

## Stack

- [ ] **`spec-0015-feeder`** — `Feeder`, `feed`, `feedFrom`, `plus`, and
      `Simulation.fedBy`.
      Done when: a feeder built from two sources fills both keys for a given
      user index, and wraps round past the end of a list.
- [ ] **`spec-0015-engine`** — the engine seeds each user's session from the
      feeder before its first step.
      Done when: a scenario reading a fed key sees a different value for each
      user, and an unfed simulation still starts from an empty session.
- [ ] **`spec-0015-adopt`** — the example sends a different order per user, and
      the README says how.
      Done when: the example's target can tell its users apart.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

Answered by the architect:

1. **The index is the user's position in the run**, counted from zero across
    the whole simulation, not per stage. A shape with three stages still
    numbers its users once.
2. **`+` is right-biased on a clash.** Two feeders filling one key is a
    mistake, but a silent one either way; the later wins, which is what
    override reads as everywhere else.
3. **A feeder is not consulted between steps.** It seeds a user once, before
    the first step. Data that changes mid-journey comes out of a response,
    which is what `capture` is for.
