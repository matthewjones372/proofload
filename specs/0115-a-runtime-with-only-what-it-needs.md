# 0115 — a runtime with only what it needs

## Problem

The image 0114 builds is 384 MB on disk, and 22 MB of that is Proofload. The
rest is `eclipse-temurin:21-jre`: a 166 MB JRE on a ~195 MB Ubuntu base, carrying
every module the platform has for an application that uses seven of them.

The obvious lever is the wrong one. `21-jre-alpine` is 329 MB and starts fine, and
every native library in the distribution ships glibc objects with no musl build —
`zstd-jni`, `snappy-java` and `lz4-java` between them have zero musl entries. On
musl the first two throw `UnsatisfiedLinkError` the first time a compressed Kafka
topic is used and not before, which trades 190 MB for a feature that breaks
silently and only in production.

## Not doing

- **Not Alpine.** See above. glibc stays.
- **Not dropping Kafka.** It is 18 MB of the 22 MB and it is a documented feature.
- **Not a custom base.** `debian:bookworm-slim` is the runtime stage; nothing is
  built from scratch.
- **Not changing what the application does.** Same distribution, same start
  script, same entry point.

## Shape

A multi-stage build: `jlink` a runtime in a JDK stage, copy it and the
distribution into a slim glibc stage.

```dockerfile
FROM eclipse-temurin:21-jdk AS runtime
RUN jlink --add-modules "$MODULES" --strip-debug --no-man-pages \
      --no-header-files --compress=zip-6 --output /javaruntime

FROM debian:bookworm-slim
COPY --from=runtime /javaruntime /opt/java
COPY build/install/proofload-mcp /opt/proofload-mcp
ENV JAVA_HOME=/opt/java PATH="/opt/java/bin:$PATH"
```

The module set is what `jdeps` found, plus what it cannot see:

```
java.base, java.logging, java.net.http, java.security.jgss,
java.security.sasl, jdk.management, jdk.unsupported, jdk.crypto.ec
```

## Why this shape

`jdk.crypto.ec` is the entry `jdeps` will never report, because EC arrives as a
security provider rather than a class reference. On 21 it is required, and a
runtime without it starts, serves and fails only when a target negotiates ECDHE.
On 25 — which this image runs — EC has moved into `java.base`, and a runtime
linked without the module does EC TLS anyway; that was measured, not assumed. It
stays in the set as insurance against the base image moving back. The rest is
`jdeps --print-module-deps` verbatim, and identical under 21 and 25.

Because of that, the guard in entry 2 asserts that the runtime can do ECDHE, not
that the module is present — dropping the module does not fail it on 25. What it
catches is a runtime minimised until HTTPS stops working.

`jlink` over a smaller base image because the base is not the problem: the JRE is,
and only `jlink` removes the part nobody uses while leaving glibc alone. The start
script finds the runtime through `JAVA_HOME`, so nothing about the distribution
changes.

## Stack

- [ ] **`spec-0115-jlink`** — the multi-stage Dockerfile, with the module set in
      one place and a comment saying why `jdk.crypto.ec` is in it.
      Done when: the image starts, `run` still refuses without an allowance and
      accepts with one, `java --list-modules` shows the set, and the image is
      materially smaller than 384 MB.
- [ ] **`spec-0115-tls`** — a check that TLS to an EC endpoint works, so a missing
      module cannot pass review again.
      Done when: the build fails if the linked runtime cannot negotiate ECDHE.
      Note that on 25 this does not fire when `jdk.crypto.ec` alone is dropped,
      because EC is in `java.base` there.

## Acceptance

```bash
./gradlew :proofload-mcp:installDist
docker build -t proofload-mcp:dev proofload-mcp
docker run --rm --entrypoint java proofload-mcp:dev --list-modules
printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' \
  | docker run -i --rm proofload-mcp:dev
```

## Open questions

1. **How is the TLS check actually run?** A real HTTPS target means traffic to
   someone else from a load-testing tool. Recommended: a local TLS server with an
   ECDSA certificate in the compose of a test, so the assertion is a handshake
   that either completes or does not, and nothing leaves the machine.
2. **`--compress=zip-6`?** It shrinks the runtime and costs start-up. For a stdio
   server started once per session, recommended: keep it.
3. **Is `java.naming` needed?** `jdeps` did not name it, and Kafka's SASL login
   modules can reach JNDI on some configurations. Recommended: leave it out and
   find out from a real SASL user, rather than adding modules on suspicion — the
   point of this is to stop carrying what nothing uses.
4. **Does the CLI want the same treatment?** It has no image and no distribution.
   Recommended: no, until it has either.
