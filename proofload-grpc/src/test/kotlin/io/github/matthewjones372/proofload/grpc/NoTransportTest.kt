package io.github.matthewjones372.proofload.grpc

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * This module carries a dependency on purpose, so the claim worth keeping is
 * which one: `grpc-api` and the small set it brings, and no transport.
 *
 * `grpc-netty-shaded` and `grpc-okhttp` each carry a thread model and a
 * networking stack, and a caller who has generated stubs has already chosen
 * one. Naming a transport here would put a second one in a process whose whole
 * job is measuring the first — and would silently decide, for every consumer,
 * the thing that most changes what a gRPC run can drive.
 */
class NoTransportTest {

    private val entries: List<String>
        get() {
            val raw = System.getProperty("proofload.grpc.runtimeClasspath")
            withClue("the build must pass -Dproofload.grpc.runtimeClasspath; see build.gradle.kts") {
                raw.shouldNotBeNull()
            }
            return raw!!.split(File.pathSeparator).filter { it.isNotBlank() }
        }

    @Test
    fun `no transport, and so no thread model chosen on a caller's behalf`() {
        val transports = entries.filter { entry ->
            listOf("grpc-netty", "grpc-okhttp", "netty-", "okhttp-", "grpc-core").any { entry.startsWith(it) }
        }

        withClue("a caller with stubs has chosen one, and forTarget finds it: $transports") {
            transports.shouldBeEmpty()
        }
    }

    @Test
    fun `no protobuf runtime, because this module never sees a proto`() {
        val generated = entries.filter { it.startsWith("protobuf-") || it.startsWith("grpc-protobuf") }

        withClue("the caller runs protoc and hands over the descriptor: $generated") { generated.shouldBeEmpty() }
    }

    @Test
    fun `no coroutines, because the seam is under the stub rather than around it`() {
        val coroutines = entries.filter { it.startsWith("kotlinx-coroutines") || it.startsWith("grpc-kotlin") }

        withClue("$coroutines") { coroutines.shouldBeEmpty() }
    }

    @Test
    fun `what is here is proofload-core, grpc-api, grpc-stub and what they bring`() {
        val allowed = listOf(
            "kotlin-stdlib", "annotations-", "proofload-core",
            // grpc-api's own, which arrive with it and are not a choice this
            // module made: the annotations and the guava/errorprone pair it
            // compiles against.
            "grpc-api", "grpc-stub", "guava", "failureaccess", "jsr305", "error_prone_annotations",
            "checker-qual", "j2objc-annotations", "listenablefuture", "grpc-context", "perfmark",
            "jspecify", "animal-sniffer-annotations",
        )

        val unexpected = entries.filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("proofload-grpc grew a dependency: $unexpected") { unexpected.shouldBeEmpty() }
    }
}
