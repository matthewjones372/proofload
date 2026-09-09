package io.github.matthewjones372.proofload.mcp

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What this module is allowed to put on a consumer's classpath, stated as a
 * test rather than promised in a document.
 *
 * A server is the modules it drives and nothing of its own: no MCP library, no
 * logging framework, no JSON library. The framing is a few hundred lines here,
 * and the sibling repository's own server is next door to read rather than to
 * depend on.
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
        // A finished run is kept as a file so it survives a restart. Pure
        // Kotlin over core, like the above.
        "proofload-baseline",
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
        "proofload-cli",
        "proofload-report-html",
        "snakeyaml-engine",
    )

    @Test
    fun `the main runtime classpath is proofload, the standard library and the one parser`() {
        val raw = System.getProperty("proofload.mcp.runtimeClasspath")
        withClue("the build must pass -Dproofload.mcp.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("proofload-mcp brings no MCP library, logger or second parser, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
