package io.github.matthewjones372.proofload.grpc.dynamic

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * This module carries protobuf's runtime on purpose — it is what turns a name
 * and a JSON object into a message when nobody generated a stub — so the claim
 * worth keeping is that it is *here* and not under `proofload-grpc`, and that no
 * transport came with it.
 */
class NoStubDependenciesTest {

    private val entries: List<String>
        get() {
            val raw = System.getProperty("proofload.grpc.dynamic.runtimeClasspath")
            withClue("the build must pass -Dproofload.grpc.dynamic.runtimeClasspath; see build.gradle.kts") {
                raw.shouldNotBeNull()
            }
            return raw!!.split(File.pathSeparator).filter { it.isNotBlank() }
        }

    @Test
    fun `what is here is proofload, grpc-api and protobuf's runtime`() {
        val allowed = listOf(
            "kotlin-stdlib", "annotations-", "error_prone_annotations", "jsr305",
            "proofload-core", "proofload-grpc",
            "grpc-api", "grpc-stub", "grpc-context",
            // gRPC's own protobuf marshaller, so the bytes on the wire are the
            // ones a generated stub would put there. The `-lite` artefact: the
            // full `grpc-protobuf` drags `Any` support and the com.google.api
            // protos with it, and nothing here needs either.
            "grpc-protobuf-lite",
            // What `grpc-services` brings for the reflection stubs. It is the
            // heaviest claim this module makes and the one worth arguing with:
            // `grpc-core` is gRPC's runtime, and the alternative was writing
            // its wire protocol here by hand.
            "grpc-services", "grpc-protobuf", "grpc-core", "grpc-util",
            "proto-google-common-protos", "protobuf-javalite", "perfmark-api", "annotations-4",
            "protobuf-java",
            // protobuf-java-util's own JSON parser, which arrives with it. It
            // is not a second parser this module chose: nothing here calls it.
            "gson",
            // gRPC's own, which arrive with `grpc-api` and are not a choice
            // this module made.
            "guava", "failureaccess", "listenablefuture", "checker-qual", "j2objc-annotations",
            "animal-sniffer-annotations", "jspecify",
        )

        val unexpected = entries.filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("proofload-grpc-dynamic grew a dependency: $unexpected") { unexpected.shouldBeEmpty() }
    }

    /**
     * `proofload-grpc` bans `grpc-core` as well, and this module cannot: the
     * reflection stubs arrive with `grpc-services`, which depends on it. The
     * claim that survives is the one that was always the point — no transport,
     * because a transport carries a thread model and the caller has already
     * chosen one. `grpc-core` is gRPC's runtime and is on the classpath of
     * anyone who can send a call at all, since every transport depends on it.
     */
    @Test
    fun `no transport, which is the half of proofload-grpc's claim that survives here`() {
        val transports = entries.filter { entry ->
            listOf("grpc-netty", "grpc-okhttp", "netty-", "okhttp").any { entry.startsWith(it) }
        }

        withClue("a transport carries a thread model, and the caller has already chosen one: $transports") {
            transports.shouldBeEmpty()
        }
    }

    @Test
    fun `protobuf is here rather than under proofload-grpc, which is the reason this module exists`() {
        withClue("if the typed path already carried protobuf's runtime, there would be nothing to split") {
            entries.filter { it.startsWith("protobuf-java") }.shouldNotBeEmpty()
        }
    }
}
