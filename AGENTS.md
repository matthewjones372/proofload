# Working in this repo

## Specs come first

Nothing is implemented without a spec file in `specs/`. An agent drafts it, a
human edits it, and only the edited, committed version gets built.

The draft is a proposal, not a plan. Its job is to be **cheap to disagree
with**, so it stays short enough to read in one sitting:

- **One page.** Roughly 80 lines. A draft that runs longer is proposing too
  much at once — split it into two specs rather than writing more.
- **No prose padding.** Fill the template's headings and stop. Where a heading
  has nothing real under it, write "nothing" and move on.
- **Uncertainty goes under Open questions**, not into a hedged paragraph under
  Shape. Three or four questions in a first draft is healthy.
- **Options, not verdicts.** Where a design could go two ways, give each a
  sentence and recommend one. Do not silently pick.

Then stop and hand it over. Do not implement a spec nobody has edited and
committed: an unreviewed draft is still the agent's own opinion, which is the
thing this process exists to stop.

Before a spec exists: ask questions, read code, answer in chat. No code.

`specs/README.md` gives the layout and the lifecycle.

## One spec section per pull request

Pull requests are stacked. Each branch sits on the one before it and is
reviewable on its own.

- **Soft cap: 200 changed lines**, excluding generated sources and golden
  fixtures. Past that, split before writing code rather than after.
- **One spec section per PR.** A spec with four stack entries is four branches,
  not one branch with four commits.
- **Announce the split first.** Post the intended stack — branch name and one
  line each — and wait for a yes before the first commit.

### Working a stack

Set this once, so a rebase carries the branches above it:

```bash
git config rebase.updateRefs true
```

Branch from `origin/main`, not from a local `main` that may be behind, and
build bottom-up with each PR based on its parent:

```bash
git switch -c spec-0003-shape origin/main
gh pr create --base main --fill

git switch -c spec-0003-engine        # branches off spec-0003-shape
gh pr create --base spec-0003-shape --fill
```

After review changes land on a lower branch, restack from the top of the stack
and push the whole chain:

```bash
git switch spec-0003-engine
git rebase origin/main
git push --force-with-lease origin spec-0003-shape spec-0003-engine
```

When the bottom PR merges, GitHub retargets its children onto `main` by itself.

## Comments

Comments record what the code cannot: the reason a thing is done the way it is.
They do not restate the code, and they are not essays.

**Write a comment when there is a why.** A surprising API, a constraint from a
dependency, a trade-off that was actually made, a bug that a naive version
reintroduces. One or two sentences.

**Do not write one when there is not.** If the signature and the body already
say it, say nothing.

### Rules

- **KDoc: one line by default.** Two or three only where the reason genuinely
  takes them. Reserve a multi-paragraph block for a decision the reader would
  otherwise undo — there should be very few in a file.
- **No worked examples in KDoc** unless the call is hard to get right from the
  signature. The README and `docs/` carry the tutorial; a code comment is not
  the place to teach the DSL twice.
- **No restating the code.** `/** The count. */ val count: Int` earns nothing.
- **No history.** "The alternative was…", "this used to…", "before this it
  was…" belongs in the commit message, not the source. Exception: naming a bug
  the comment exists to stop coming back.
- **No rhetorical framing.** Skip "Note the split of responsibilities", "which
  is the whole point", "and that is the difference that buys". State the fact.
- **Inline `//` notes are for the line below them**, not for paragraphs. If it
  runs past three lines, it is either KDoc or it is too long.

Same rules in test sources. A test name should carry the claim; the KDoc above
it should not repeat the name in longer words.

## Imports

**No wildcard imports, anywhere, and no unused ones.** One line per name, so an
import block is an inventory of what a file uses and where each piece lives —
and so a reader of the documentation's examples can see exactly what to import.
Two detekt rules fail the build on either mistake, `WildcardImport` and
`UnusedImport`, and `.editorconfig` tells ktlint and the IDE the same thing so
an optimize-imports cannot put a star back.

The same holds in the docs and the examples: every complete example carries its
imports written out, and the `dependencies { }` block naming the modules it
needs, with real coordinates. A reader is copying into a project that has none
of this in scope.

## Layout

`kestrel-core` depends on the Kotlin standard library and nothing else.
Everything with a third-party type in it is a leaf module: an HTTP client, a
reporting format, a metrics sink.

Every dependency claim in `docs/modules.md` is a test.
`NoThirdPartyDependenciesTest` asserts core's runtime classpath, and each leaf
module asserts it carries its own dependency and no second stack.

A dependency added to core is a build failure, not a judgement call. Core
declares an interface; an adapter module carries the library.

## Values, errors and effects

Descriptions are values. A scenario is a value that can be built, inspected,
split across files, filtered and compared — not a builder that runs as it is
called. There is no registry and nothing that has to run at startup. Prefer a
value to a function when adding a feature.

Errors a caller was promised are values in the return type. Throwing is for
what nobody declared — a broken assumption, a bug.

Do not wrap work in `runCatching` and map the result into a failure. That
produces a second error model beside the declared one.

Never add an `else` to a `when` over a sealed type. The missing branch is the
compiler naming a case that needs handling.

Public API returns read-only types.

**`var` is a last resort, not a default.** Prefer `val`, a fold, or a derived
property over a counter that is incremented. A mutable accumulator is allowed
in exactly two places: inside a builder that freezes it before returning, and
on a path whose allocation would be measured as the target's latency. Both
carry a comment saying which.

A number that can be derived is derived. Two fields that must be kept in step
are one field and a function.

## Measurement

This is a load generator, so its own overhead is part of its correctness.

- **A number in a report is a measurement or it is a lie.** Nothing is
  estimated, smoothed or interpolated without the code saying so where it
  happens and the report saying so where it prints.
- **Never park a thread to wait.** `Thread.sleep` in library code turns a wait
  into a stall that the tool then measures as the target's latency. detekt
  forbids it outside tests; pacing belongs on a scheduler.
- **Coordinated omission is the default bug.** A generator that waits for a
  response before sending the next request measures a queue it created. Where
  the design admits it, say so in the spec and in the docs rather than in a
  comment nobody reads.
- **A benchmark of the tool is not a test of the tool.** Keep them apart, and
  keep the harness out of the coverage denominator.

## Testing

Write the failing test first. A test written afterwards asserts what the
implementation does rather than what the description promised.

Test names are sentences in backticks. Kotest matchers, JUnit 5, `withClue`
where a bare boolean would not explain itself.

Work out which of these a change can break:

- **Contract tests** through the public API, naming values rather than strings.
- **Golden files** for anything a user reads — a report, a summary line. A
  moved golden is the test working: read the diff and decide whether the break
  is intended; do not regenerate for green.
- **Timing claims** need a gate, not a sleep. Assert that the first result
  arrives well before the last, rather than that a run took N seconds.
- **A test that measures elapsed time cannot share a machine.** A 40 ms target
  measured 161 ms during a parallel build. Tag those `timing`, keep them out of
  `test`, and run them alone with `./gradlew :examples:timingTests`.

  The tag is not what keeps them out of `./gradlew build`. Kover instruments
  every test task in a module it aggregates and `check` depends on
  `koverVerify`, so the task has to be excluded from instrumentation in its own
  build file as well — `examples/build.gradle.kts` shows the shape. Gradle
  fails the build when a tagged task shares a task graph with any other test
  task, so this is a gate rather than a convention. CI runs them after `build`,
  in a step of their own.

## Verifying

`./gradlew build` runs tests, detekt and spotless. Run it before saying
anything is done, and quote the result rather than predicting it.

**Finish with `./gradlew spotlessApply`.** Formatting is a gate like any other,
and a change that is correct but unformatted fails the build for whoever runs
it next. Run it last, after the final edit, so nothing lands unformatted:

```bash
./gradlew spotlessApply && ./gradlew build
```

Gates that sit beyond the tests:

| Gate | Fails when | Not the fix |
|---|---|---|
| detekt | any finding | a suppression with no reason |
| Kover | aggregate line coverage under the floor | lowering the floor |
| `NoThirdPartyDependenciesTest` | core grew a dependency | adding it to the allowlist |
| wall-clock isolation | a `timing` task is in the same task graph as another test task | dropping the tag |

Before saying it is done:

- The failing test came first, and fails without the change.
- `./gradlew build` is green, gates included.
- If a caller-visible behaviour changed, the documentation changed with it.
- No new dependency in `kestrel-core`.
