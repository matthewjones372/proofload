package io.github.matthewjones372.proofload.report

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The claim in `specs/0007` that this module costs a consumer nothing but core,
 * stated as a test. A markdown table and a file named by an environment
 * variable need no library, so a dependency arriving here is a design change
 * and not a detail.
 */
class NoThirdPartyDependenciesTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "proofload-core")

    @Test
    fun `the main runtime classpath is the Kotlin standard library and proofload-core, and nothing else`() {
        val raw = System.getProperty("proofload.report.github.runtimeClasspath")
        withClue("the build must pass -Dproofload.report.github.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("proofload-report-github must carry no library, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
