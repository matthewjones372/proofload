# 0112 — a server you can start

## Problem

Adding the MCP server to a client takes a clone, a JDK, a Gradle build and an
absolute path into `build/`:

```bash
./gradlew :proofload-mcp:installDist
claude mcp add proofload -- "$PWD/proofload-mcp/build/install/proofload-mcp/bin/proofload-mcp"
```

Four things to get right before one tool is callable, ending on a path that does
not survive `./gradlew clean`. Anyone who wants to try the server has to want it
enough to build the repository first.

Every module is on Maven Central now, so a launcher that resolves coordinates can
start it without any of that. What stops it is small: `application` sets
`mainClass` for the start scripts it generates and nothing writes `Main-Class`
into the published jar, so a launcher must be told the class by hand.
`proofload-cli` is worse off — a `main` in `Cli.kt` that nothing declares, so the
command line has no documented way to run outside Gradle.

## Not doing

- **No fat jar.** The module's own comment refuses one, and resolving the POM
  makes it pointless.
- **No installer script, no tap, no published `distZip`.** That is the
  zero-prerequisite answer and it is a different spec with a hosting question.
- **Not adding `application` to `proofload-cli`.** A manifest makes it
  launchable; start scripts are a separate want.
- **Not removing `installDist`.** It stays documented, for a contributor running
  an unreleased build.

## Shape

```bash
claude mcp add proofload -- jbang io.github.matthewjones372:proofload-mcp:VERSION
jbang io.github.matthewjones372:proofload-cli:VERSION validate plan.yaml
```

```kotlin
// each module's build.gradle.kts
tasks.jar {
    manifest { attributes("Main-Class" to "io.github.matthewjones372.proofload.mcp.ServerKt") }
}
```

## Why this shape

Two lines and no new artefact, against an installer or a tap which are both a
release process. It only helps launchers that resolve the POM — `java -jar` still
cannot work, a thin jar carrying no classpath — which the docs should say rather
than leave someone to discover.

`jbang` leads because it is one binary and one line; `coursier` is named for
anyone who has it. Neither is required: `installDist` stays for anyone who wants
no launcher at all.

The catch is that `0.1.0-rc1` is published and immutable, so the short form works
only from the next release. Until then the docs show the form that works,
`--main io.github.matthewjones372.proofload.mcp.ServerKt` — the honest cost of
shipping the manifest after a first release rather than before.

## Stack

- [ ] **`spec-0112-manifest`** — `Main-Class` in the published jars of
      `proofload-mcp` and `proofload-cli`.
      Done when: `unzip -p` shows the attribute on both, and the server answers
      `initialize` started from a classpath resolved off Maven Central rather
      than from `build/`.
- [ ] **`spec-0112-docs`** — the README's MCP section and `docs/mcp.md` lead with
      the launcher, `installDist` below it.
      Done when: the documented command names a version that resolves, and says
      `java -jar` is not one of the ways.

## Acceptance

```bash
./gradlew :proofload-mcp:jar :proofload-cli:jar
unzip -p proofload-mcp/build/libs/proofload-mcp-*.jar META-INF/MANIFEST.MF | grep Main-Class
unzip -p proofload-cli/build/libs/proofload-cli-*.jar META-INF/MANIFEST.MF | grep Main-Class
./gradlew build
```

## Open questions

1. **`jbang` or `coursier` first?** Recommended: `jbang`, one binary and no
   subcommand in its coordinate form.
2. **Document the long `--main` form now, or only the short one after the next
   release?** Recommended: document what works today; a README command that
   fails is worse than a verbose one.
3. **`application` for `proofload-cli` as well?** Recommended: no here — the
   manifest is what makes it launchable.
4. **Pin the version in docs, or a `VERSION` placeholder?** A pin goes stale
   every release, a placeholder cannot be copied. Recommended: pin, and check
   whether anything already bumps versions in docs at release time.
