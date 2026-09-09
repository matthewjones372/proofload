# 0054 — A feeder that reads a file

## Problem

`feed(key) { user -> "customer-$user" }` covers generated data, and
`feedFrom(key, values)` covers a list somebody already has in hand. The case
between them is the common one: a file of real account ids, several columns
wide, that a team was given by whoever owns the test environment.

Getting there today is a page of setup in every project — read the file, drop
the header, split on commas, transpose into one list per column, call
`feedFrom` per column and `+` them together. It is the same page every time and
it is where the mistakes are: a header row fed to user zero, a quoted field
with a comma in it split into two, a file that shrank between runs and moved
every user's data.

## Not doing

- No JDBC, no JSON, no Parquet. One format, and the one people are handed.
- No shuffle, no circular, no random strategies. The user's number is the
  index; that is the whole reproducibility argument and it is not negotiable.
- No streaming from disk during a run. The file is read once, before anything
  departs.
- No writing. A feeder reads.

## Shape

```kotlin
import io.github.matthewjones372.proofload.csv

val customer = sessionKey<String>("customer")
val tier = sessionKey<String>("tier")

val accounts = csv(Path.of("accounts.csv"))   // the header row names the columns

accounts.rows                                  // 12,000, before anything is sent

val fed = checkout.at(50.perSecond, over = 1.minutes)
    .fedBy(accounts.feeding(customer, tier))
```

- `csv(path)` reads once and returns a value: `rows`, `columns`, and the data.
- `feeding(vararg keys)` maps a column to the key of the same name, and fails
  loudly at build time when a named column is not in the file.
- `feeding(key) { text -> text.toLong() }` for a column that is not text.
- Wraps at the end, as `feedFrom` already does.

## Why this shape

Read once, before the run, because that is the same argument the whole `Feeder`
design rests on: nothing on the path a departure takes may touch a lock, a
cursor or a disk. A streaming feeder would put a file read between the
departure and the request and measure it as the target's latency.

Column names rather than indices. A file gains a column and every index-based
mapping shifts by one, silently, and the run still passes — with every user
sent the wrong tier.

Where it lives is the layering question. `java.nio.file` is the JDK rather than
a third party, so `proofload-core` can hold this without breaking
`NoThirdPartyDependenciesTest`. Recommend core, with a deliberately small
RFC 4180 subset — quoted fields, doubled quotes inside them, no embedded
newlines — and the subset stated on the function rather than discovered. A
`proofload-feeders` module for sixty lines of parsing is ceremony; a full CSV
library in core is a dependency the rule exists to stop.

## Stack

- [x] **`spec-0054-csv`** — `csv(path)`, the parse, the header, `rows` and
      `columns` as values.
      Done when: a quoted field containing a comma is one field, a header is
      never row zero, and an empty file is refused by name.
- [x] **`spec-0054-feeding`** — `feeding(vararg keys)` and the conversion
      overload, with the failure when a column is missing.
      Done when: two keys fill from two columns, and a key naming a column that
      is not there fails before the run rather than during it.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Core or a leaf module?** Recommend core, on the argument above. The
    counter-argument is that core has held only the shapes of the domain so
    far and a parser is not one; if that matters more, `proofload-feeders` on
    core is the alternative and costs a module.
2. **What happens when there are more users than rows?** Recommend wrapping,
    matching `feedFrom`, and recommend `rows` being a value so a caller who
    cares can assert on it. Refusing would end a load test for a reason that
    has nothing to do with the target.
3. **Are columns always `String`?** Recommend yes, with the conversion overload
    for anything else, because a CSV has no types and inferring them would make
    the same file parse differently as its data changed.
4. **How large a file is too large?** The whole thing is held in memory, and a
    generator competing with its target for memory measures itself. Recommend
    stating the arithmetic in `docs/what-it-costs.md` rather than imposing a
    cap nobody can predict.
