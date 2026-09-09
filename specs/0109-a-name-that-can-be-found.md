# 0109 — a name that can be found

## Problem

The project is called Kestrel, and so is the web server built into ASP.NET
Core. That collision is not cosmetic: it sits in this project's own subject.
Searching for the tool alongside any performance word returns Microsoft's
server and articles about load testing *it* — the query a prospective user
types is the query that cannot reach us. A load generator nobody can look up
is one nobody adopts, and no amount of README work moves a result page owned
by a decade of Microsoft documentation.

Renaming is cheapest now and never gets cheaper: there are no git tags, and
nothing has gone to Maven Central, so no published coordinate breaks and no
downstream build has to change. The cost is entirely internal — a mechanical
substitution across the tree.

## Not doing

- Not changing any behaviour, signature shape, or module boundary. The rename
  is textual; every type keeps its members and every module keeps its
  dependencies.
- Not redesigning the report, the docs, or the DSL while the tree is open.
- Not renaming `io.github.matthewjones372` — only the trailing `kestrel`
  segment moves.
- Not chasing a second opinion on the name. Proofload was chosen against a
  checked shortlist; alternatives died on collisions (Metronome is IBM's
  real-time JVM collector, Ballast is both a Kotlin state framework and an
  existing load tester, Vernier, Gnomon, Lodestar and Sextant are all taken).

## Shape

The name a user writes is the type the extension injects, so the rename shows
up first in the call every test makes:

```kotlin
import io.github.matthewjones372.proofload.engine.Proofload

class CheckoutLoadTest {

    @LoadTest
    fun `checkout holds up at fifty a second`(proofload: Proofload) {
        val result = proofload.run(checkout.at(50.perSecond, over = 1.minutes))

        assertTrue(result[placeOrder].responseTime.p99 < 200.milliseconds)
    }
}
```

Coordinates follow the module directories:

```kotlin
testImplementation("io.github.matthewjones372:proofload-http:0.1.0")
```

Everything else is the same substitution applied consistently: 27 module
directories, 65 package directories, the exported Prometheus metric prefix
(`kestrel_latency_seconds` becomes `proofload_latency_seconds`), the allowance
file `kestrel.toml`, and the `.api` dumps.

## Why this shape

"Proofload" is the engineering term for a test load applied to prove a
structure holds — which is the claim this tool exists to make, and it is
unclaimed in JVM tooling. The alternative was to keep Kestrel and invest in
the README instead; that loses, because the problem is a search result page
rather than a first impression, and prose cannot fix it.

Two casing variants exist in the tree and no others, so a two-rule
substitution is exhaustive rather than approximate — verified by there being
zero remaining matches afterwards.

The bird mark is replaced rather than kept. A falcon silhouette on a project
named after a load test reads as an unrelated logo, so the mark becomes a load
bearing down on a line that does not bend. It is deliberately plain and is the
one part of this change worth an opinion.

## Stack

One entry: a rename that is not reviewable in pieces. Splitting it would leave
`main` in a state where half the modules resolve, so the ~200 line cap is
waived here for a mechanical change that is verified by the build.

- [x] **`claude/cool-bardeen-cxp6k9`** — the substitution across the tree, the
      new mark, and this spec.
      Done when: `grep -ri kestrel` returns nothing and `./gradlew build` is
      green.

## Acceptance

```bash
grep -ri kestrel . --exclude-dir=.git   # no output
./gradlew build
```

## Open questions

1. **Does the GitHub repository get renamed too?** Recommend yes —
   `matthewjones372/proofload`, with the description updated to match. GitHub
   redirects the old path, so nothing breaks. This needs the owner; it cannot
   be done from a branch.
2. **Is the placeholder mark good enough to ship?** Recommend treating it as a
   placeholder. It is geometrically clean but it is not a designed identity.
3. **Does the metric prefix need a compatibility alias?** Recommend no.
   Nothing is published, so no dashboard exists that reads
   `kestrel_latency_seconds`.
