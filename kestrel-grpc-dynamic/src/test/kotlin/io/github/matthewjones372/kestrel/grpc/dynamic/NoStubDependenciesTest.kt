package io.github.matthewjones372.kestrel.grpc.dynamic

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * This module carries protobuf's runtime on purpose — it is what turns a name
 * and a JSON object into a message when nobody generated a stub — so the claim
 * worth keeping is that it is *here* and not under `kestrel-grpc`, and that no
 * transport came with it.
 */
class NoStubDependenciesTest {

    private val entries: List<String>
        get() {
            val raw = System.getProperty("kestrel.grpc.dynamic.runtimeClasspath")
            withClue("the build must pass -Dkestrel.grpc.dynamic.runtimeClasspath; see build.gradle.kts") {
                raw.shouldNotBeNull()
            }
            return raw!!.split(File.pathSeparator).filter { it.isNotBlank() }
        }

    @Test
    fun `what is here is kestrel, grpc-api and protobuf's runtime`() {
        val allowed = listOf(
            "kotlin-stdlib", "annotations-", "error_prone_annotations", "jsr305",
            "kestrel-core", "kestrel-grpc",
            "grpc-api", "grpc-stub", "grpc-context",
            // gRPC's own protobuf marshaller, so the bytes on the wire are the
            // ones a generated stub would put there. The `-lite` artefact: the
            // full `grpc-protobuf` drags `Any` support and the com.google.api
            // protos with it, and nothing here needs either.
            "grpc-protobuf-lite",
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

        withClue("kestrel-grpc-dynamic grew a dependency: $unexpected") { unexpected.shouldBeEmpty() }
    }

    @Test
    fun `no transport, for the same reason kestrel-grpc names none`() {
        val transports = entries.filter { entry ->
            listOf("grpc-netty", "grpc-okhttp", "grpc-core", "netty-", "okhttp").any { entry.startsWith(it) }
        }

        withClue("a transport carries a thread model, and the caller has already chosen one: $transports") {
            transports.shouldBeEmpty()
        }
    }

    @Test
    fun `protobuf is here rather than under kestrel-grpc, which is the reason this module exists`() {
        withClue("if the typed path already carried protobuf's runtime, there would be nothing to split") {
            entries.filter { it.startsWith("protobuf-java") }.shouldNotBeEmpty()
        }
    }
}
