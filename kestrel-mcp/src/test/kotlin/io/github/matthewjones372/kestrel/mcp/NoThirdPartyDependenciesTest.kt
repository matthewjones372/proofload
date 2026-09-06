package io.github.matthewjones372.kestrel.mcp

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
        "kestrel-core",
        "kestrel-plan",
        "kestrel-http",
        "kestrel-engine",
        "kestrel-export",
        "kestrel-openapi",
        "kestrel-cli",
        "kestrel-report-html",
        "snakeyaml-engine",
    )

    @Test
    fun `the main runtime classpath is kestrel, the standard library and the one parser`() {
        val raw = System.getProperty("kestrel.mcp.runtimeClasspath")
        withClue("the build must pass -Dkestrel.mcp.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("kestrel-mcp brings no MCP library, logger or second parser, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
