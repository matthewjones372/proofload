package io.github.matthewjones372.kestrel.openapi

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
        "kestrel-core",
        "kestrel-http",
        "kestrel-plan",
        "snakeyaml-engine",
    )

    @Test
    fun `the main runtime classpath is a plan and one parser, with no Pelican in it`() {
        val raw = System.getProperty("kestrel.openapi.runtimeClasspath")
        withClue("the build must pass -Dkestrel.openapi.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("kestrel-openapi carries a parser and a plan, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
