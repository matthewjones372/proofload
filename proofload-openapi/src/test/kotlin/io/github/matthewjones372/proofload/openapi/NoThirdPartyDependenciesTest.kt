package io.github.matthewjones372.proofload.openapi

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What this module is allowed to put on a consumer's classpath, stated as a
 * test rather than promised in a document.
 *
 * A document reader carries the plan it writes and the parser that reads one,
 * and nothing else — no Pelican, because most people with an OpenAPI document
 * do not have a Pelican service and should not take one to read it.
 */
class NoThirdPartyDependenciesTest {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "proofload-core",
        "proofload-http",
        "proofload-plan",
        // Arrives with `proofload-plan`, which draws a value per user. Pure
        // Kotlin over core, so no third-party jar comes with it.
        "proofload-arbs",
        "snakeyaml-engine",
    )

    @Test
    fun `the main runtime classpath is a plan and one parser, with no Pelican in it`() {
        val raw = System.getProperty("proofload.openapi.runtimeClasspath")
        withClue("the build must pass -Dproofload.openapi.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("proofload-openapi carries a parser and a plan, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
