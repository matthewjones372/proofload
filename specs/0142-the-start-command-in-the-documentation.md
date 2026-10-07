# 0142 — The start command in the documentation

## Problem

The command `README.md` and `docs/mcp.md` tell a reader to run starts the wrong
program, and the download they name is not there.

```
$ cs launch io.github.matthewjones372:proofload-mcp:0.1.0-rc4
proofload validate <plan>          parse it, resolve it, send nothing
...
exit codes: 0 met, 1 missed a goal, 2 the generator fell behind, 3 refused, 4 unusable
```

The jar's manifest is right — `Main-Class: …mcp.ServerKt`. Coursier does not
read it in isolation: it collects the `Main-Class` of every jar on the resolved
classpath, keys each by (`Implementation-Vendor-Id`, `Specification-Title`) with
both defaulting to the empty string, and where two jars key alike it keeps one
of them. `proofload-mcp` and `proofload-cli` both carry a main and neither names
itself, so the two collapse into one entry and the command line is what starts.
A client sees usage text and exit code 4. jbang reads the manifest of the jar it
was asked for and is unaffected, so the two launchers the pages call
interchangeable are not.

Separately, `docs/mcp.md` pins
`…/releases/download/v0.1.0-rc4/proofload-mcp-0.1.0-rc4.zip`, which is a 404.
The zip was built and attached, but the release was left drafted while the
modules went to Maven Central, so the asset sat under an `untagged-…` URL.
Nothing checked the page against the release.

## Not doing

- **No fat jar.** The reason a launcher is documented rather than a download is
  that there is nothing to keep in step with the modules it would shade.
- **No new MCP tool, and no change to any tool's schema.** This is packaging.
- **No `-M` in the documented command.** Naming the class works and is what a
  reader has to do today; it is the workaround, not the fix.
- **Nothing about the GHCR image.** It is still private and `docs/mcp.md`
  already says so.
- **No change to the release staying a draft.** Nothing is public until a person
  looks, which is the point of it.

## Shape

Every published jar names the coordinate it came from, so a launcher can tell
two mains apart:

```kotlin
// build.gradle.kts, for every published module
tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Implementation-Vendor-Id" to project.group.toString(),
            "Specification-Title" to project.name,
        )
    }
}
```

Two gates, because the two failures are found in different places:

```
proofload-mcp/src/test/.../LaunchingTest
  opens every jar this coordinate resolves, and fails when two carry a main
  under the same name or when coursier's own rule picks anything but ServerKt

.github/workflows/downloads.yml
  fails when a `releases/download/` URL in README.md or docs/*.md is not 200
```

## Why this shape

Naming every published jar rather than only the two that carry a main: the
collision is between jars, and a third module growing an entry point would bring
it back silently. The attributes are true of every artifact anyway.

`LaunchingTest` writes coursier's four-line matching rule out rather than
depending on the launcher to test what it does. Depending on it would tie this
build's tests to that release train for the same reason the JSON-RPC framing is
written here rather than taken from a library. The rule is quoted in the test's
KDoc with the class it comes from, and the release workflow runs the real
launcher against the local repository, so the copy is checked against the
original once per tag rather than never.

The URL check cannot live in `./gradlew build`: the asset does not exist when
the tests run. It runs when a release is published, and on the clock for the day
somebody publishes to Central and forgets the draft — which is exactly what
happened.

The alternative for the download was to drop the pinned URL for a link to the
releases page. Recommend against: the page's value is a command a reader pastes,
and a version-pinned asset is what makes it one.

## Stack

- [x] **`spec-0142-manifest`** — the naming attributes, and `LaunchingTest`.
      Done when: `cs launch io.github.matthewjones372:proofload-mcp:VERSION` with
      no `-M` answers `tools/list` with `benchmark`, against a repository built
      from this commit.
- [x] **`spec-0142-gates`** — the launcher run in the release workflow, and
      `downloads.yml`.
      Done when: a 404 in a documented download URL fails a job rather than a
      reader's `curl`.
- [x] **`spec-0142-docs`** — `docs/mcp.md` and the README stop calling the two
      launchers interchangeable while a pinned version needs `-M`.
      Done when: no page carries a command that starts the command line.

## Acceptance

```bash
./gradlew build
```

Then, against a repository built from the commit:

```bash
./gradlew publishAllPublicationsToLocalRepository
printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' \
  | cs launch --no-default -r "file://$PWD/build/repo" -r central \
      io.github.matthewjones372:proofload-mcp:0.1.0-rc4
```

## Open questions

- **Should the release workflow stop drafting the release?** Recommend no: the
  draft is deliberate, and the gap this spec closes is that nothing noticed the
  draft outliving the Central release, not that drafts exist.
- **Does the caveat in `docs/mcp.md` come out when the pin moves past
  `0.1.0-rc4`?** Recommend yes, and nothing gates it — the sentence is about
  versions already published and cannot be made true again for them.
