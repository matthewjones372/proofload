# The server as an image, for anyone who wants neither a JDK nor a launcher.
#
# Built from the distribution `application` already produces rather than from a
# shaded jar: the same `bin/proofload-mcp` and the same jars beside it, so there
# is nothing here to keep in step with the modules a fat jar would have shaded.
# Run `./gradlew :proofload-mcp:installDist` before building.

# The modules this application actually uses. The first seven are
# `jdeps --print-module-deps` verbatim, and the same seven under 21 and 25.
#
# The runtime is 25 and the bytecode in the jars is 21: `jvmToolchain(21)` is
# what published artifacts have to keep working on, and `build.yml` runs the
# tests on 21, 23 and 25. A newer runtime under older bytecode is the supported
# direction; the reverse is not, which is why this is not raised in the build.
#
# `jdk.crypto.ec` is the eighth, and it is here as insurance rather than
# necessity. On 21 it is required — EC arrives as a security provider, so `jdeps`
# cannot see it, and a runtime without it starts, serves, and fails only when a
# real target negotiates ECDHE. On 25 EC has moved into `java.base` and a runtime
# linked without this module does EC TLS anyway, which was checked rather than
# assumed. It is kept so that moving this base image back to 21 does not silently
# lose HTTPS against most of the internet.
ARG MODULES="java.base,java.logging,java.net.http,java.security.jgss,java.security.sasl,jdk.management,jdk.unsupported,jdk.crypto.ec"

# A runtime with those and nothing else. A stock JRE is ~166 MB of platform for
# an application that uses eight modules of it; this is the only lever that
# removes the unused part without leaving glibc, which `zstd-jni`, `snappy-java`
# and `lz4-java` all need — none of them ships a musl build.
FROM eclipse-temurin:25-jdk AS runtime
ARG MODULES
RUN jlink \
      --add-modules "$MODULES" \
      --strip-debug \
      --no-man-pages \
      --no-header-files \
      --compress=zip-6 \
      --output /javaruntime

# A guard on the property that matters: that this runtime can do EC TLS at all.
# It is not a guard on the module — on 25 EC is in `java.base`, so dropping
# `jdk.crypto.ec` still passes this, which was measured. What it does catch is a
# runtime minimised until HTTPS stops working, which is the failure this whole
# stage risks. Compiled here because the linked runtime has no compiler.
RUN mkdir /check && printf '%s\n' \
  'public class EcCheck {' \
  '  public static void main(String[] args) throws Exception {' \
  '    java.security.KeyPairGenerator.getInstance("EC");' \
  '    String[] suites = javax.net.ssl.SSLContext.getDefault()' \
  '        .getSupportedSSLParameters().getCipherSuites();' \
  '    for (String suite : suites) if (suite.contains("ECDHE")) return;' \
  '    throw new IllegalStateException("this runtime cannot do ECDHE: no such suite");' \
  '  }' \
  '}' > /check/EcCheck.java \
  && javac -d /check /check/EcCheck.java

FROM debian:bookworm-slim

# `jlink` brings the JDK's own `cacerts` with it, so Java's trust store travels
# with the runtime and this base needs no CA package of its own.
COPY --from=runtime /javaruntime /opt/java
ENV JAVA_HOME=/opt/java
ENV PATH="/opt/java/bin:${PATH}"

# Fails the build if the linked runtime cannot do ECDHE. Deleted in the same
# layer, so the check leaves nothing in the image it was checking.
COPY --from=runtime /check/EcCheck.class /tmp/EcCheck.class
RUN java -cp /tmp EcCheck && rm /tmp/EcCheck.class

# The start script and the jars, exactly as `installDist` laid them out.
COPY build/install/proofload-mcp /opt/proofload-mcp

# `Allowance.fromFile()` reads its file from the working directory, so the fence
# is a mount and needs no setting of its own:
#   -v "$PWD/proofload.toml:/work/proofload.toml:ro"
WORKDIR /work

# An absent allowance means no limits, which is right for someone who installed a
# load generator on purpose and wrong for an image a model drives. Set here and
# nowhere else, so a local install behaves as it always has. Only `run` consults
# it — `preview`, `smoke` and `trace` stay usable with nothing mounted.
ENV PROOFLOAD_REQUIRE_ALLOWANCE=1

# Nothing here needs root, and a public image that runs as it is one hop shorter
# for anyone who finds a way out of the JVM.
RUN useradd --create-home --uid 10001 proofload && chown -R proofload /work
USER proofload

# stdio, so no port and no shell form — the protocol is stdin and stdout and a
# wrapper shell would sit between the client and the stream.
ENTRYPOINT ["/opt/proofload-mcp/bin/proofload-mcp"]
