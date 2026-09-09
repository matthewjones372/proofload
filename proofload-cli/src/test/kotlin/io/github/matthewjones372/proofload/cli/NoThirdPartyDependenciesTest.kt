package io.github.matthewjones372.proofload.cli

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What this module is allowed to put on a consumer's classpath, stated as a
 * test rather than promised in a document.
 *
 * A command line is the modules it drives and nothing of its own: no argument
 * parser, no logging framework, no JSON library. Everything it prints is
 * something the library already writes.
 */
class NoThirdPartyDependenciesTest {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "proofload-core",
        "proofload-plan",
        // Arrives with `proofload-plan`, which draws a value per user. Pure
        // Kotlin over core, so no third-party jar comes with it.
        "proofload-arbs",
        // A plan may name a topic, so a tool that reads plans carries the
        // module that lowers one and the client it brings. A library consumer
        // taking `proofload-plan` alone still gets neither.
        "proofload-plan-kafka",
        "proofload-kafka",
        "kafka-clients",
        "zstd-jni",
        "lz4-java",
        "snappy-java",
        "slf4j-api",
        "proofload-http",
        "proofload-engine",
        "proofload-export",
        "proofload-openapi",
        "snakeyaml-engine",
    )

    @Test
    fun `the main runtime classpath is proofload, the standard library and the one parser`() {
        val raw = System.getProperty("proofload.cli.runtimeClasspath")
        withClue("the build must pass -Dproofload.cli.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("proofload-cli brings no parser, logger or framework of its own, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
