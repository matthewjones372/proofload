package io.github.matthewjones372.proofload.report

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What this module is allowed to put on a consumer's classpath, stated as a
 * test rather than promised in a document.
 *
 * The whole argument for a hand-written page is that it still opens years from
 * now with nothing fetched and nothing resolved. A JSON library or a templating
 * engine here would be the first half of that promise broken.
 */
class NoThirdPartyDependenciesTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "proofload-core")

    @Test
    fun `the main runtime classpath is the Kotlin standard library and proofload-core, and nothing else`() {
        val raw = System.getProperty("proofload.report.html.runtimeClasspath")
        withClue("the build must pass -Dproofload.report.html.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("proofload-report-html must carry no library of its own, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
