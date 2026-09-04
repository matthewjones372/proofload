package io.github.matthewjones372.kestrel.cli

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
        "kestrel-core",
        "kestrel-plan",
        "kestrel-http",
        "kestrel-engine",
        "kestrel-export",
        "snakeyaml-engine",
    )

    @Test
    fun `the main runtime classpath is kestrel, the standard library and the one parser`() {
        val raw = System.getProperty("kestrel.cli.runtimeClasspath")
        withClue("the build must pass -Dkestrel.cli.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("kestrel-cli brings no parser, logger or framework of its own, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
