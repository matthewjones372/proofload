# Versions and compatibility

## Which version to use

`0.1.0-rc4` is the current release on Maven Central. Every coordinate in these
documents is pinned to it. All modules share one version.

`0.1.0` has not been released. Until it is, the API can still change between
release candidates; [CHANGELOG.md](../CHANGELOG.md) lists what has changed and
its known limitations. The CHANGELOG does not separate what is in `rc4` from what
is only on `main`; `git log v0.1.0-rc4..main` does.

Release candidates after `rc4` are published to Maven Central's snapshot
repository rather than to Central itself, and that repository drops them after
90 days. To use what is on `main`, build it:

```bash
./gradlew publishToMavenLocal
```

## On `main`, not in `0.1.0-rc4`

These documents describe `main`. Where `rc4` behaves differently, it is listed
here:

- **Choosing the clock for a comparison.** `result.against(previous, of = Clock.ServiceTime)`,
  `Results.against(result, previous, 99.0, Clock.ServiceTime)` from Java and the
  Scala equivalent are on `main` only. In `rc4`, `against` always compares
  response time.
- **Timeline memory.** On `main` a long run's timeline stops growing after 3,600
  slots, by widening its slots, and closed seconds are compacted
  ([what-it-costs.md](what-it-costs.md#what-the-recorder-keeps)). This changed the
  constructors of `Second`, `Simulation` and `RunRecorder`.
- **Coursier and the MCP server.** On `main`, `cs launch` on the
  `proofload-mcp` coordinate starts the server. With `rc4` and earlier, pass
  `-M io.github.matthewjones372.proofload.mcp.ServerKt`.

## Known incompatibilities

- **Do not use `0.1.0-rc3` from Scala.** It was compiled with Scala 3.9 and
  cannot be read by a Scala compiler older than 3.9. `rc4` is compiled with the
  Scala 3.3 LTS line again, and a test now fails the build if the emitted TASTy
  version rises ([from-scala.md](from-scala.md)).
- **`proofload-mcp` `0.1.0-rc1`** has no `Main-Class` in its jar. Start it with
  `--main io.github.matthewjones372.proofload.mcp.ServerKt`.
- **`proofload-junit5`** depends on `junit-jupiter-api` from the JUnit 6 line.
  The module name refers to the Jupiter programming model.
