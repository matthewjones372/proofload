# The recorded API

`api/<module>.api` in every published module, written by
[binary-compatibility-validator](https://github.com/Kotlin/binary-compatibility-validator)
and checked by `apiCheck`, which runs from `check` — so `./gradlew build` fails
on an unrecorded break exactly as it fails on a detekt finding.

```bash
./gradlew apiDump    # rewrite the dumps after a deliberate change
./gradlew apiCheck   # fails where the surface moved and the dumps did not
```

This **records** the API; it does not freeze it. Until 1.0 a break is allowed —
what changed is that it now arrives as a line removed from a file a reviewer is
already looking at, beside the change that removed it, rather than as something
somebody remembered to write in the CHANGELOG a week later.

## Proving the gate

A test cannot make this claim: breaking a module's public API from inside that
module breaks the module the test lives in. So there is a script, and a
transcript of it running.

```console
$ ./config/api/proves-the-gate.sh
--- removed 'fun Progress.throttled' from the public surface; running ./gradlew apiCheck
--- apiCheck failed and named it, which is the gate working:
41:  -	public static final fun throttled-HG0u8IE (Lio/github/matthewjones372/proofload/Progress;J)Lio/github/matthewjones372/proofload/Progress;
```

The script puts the function back on the way out, whatever happens. Run it after
touching the plugin's configuration; it takes about a minute.

## Reading a dump

A line is a JVM signature, so it says what a *caller's compiled class* depends
on rather than what the Kotlin source looks like. Two consequences catch people
out:

- **A value class mangles the name it is called under.** `throttled-HG0u8IE`
  above is `Progress.throttled(Duration)`: `Duration` is a value class, so the
  hash is part of the JVM name and changing the parameter's type changes it.
- **A default argument is a second method.** Adding one to an existing function
  adds a `$default` line rather than changing the one that was there, which is
  why it is source-compatible and binary-compatible at once.
