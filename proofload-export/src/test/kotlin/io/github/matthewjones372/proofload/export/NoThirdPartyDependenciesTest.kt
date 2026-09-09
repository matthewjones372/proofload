package io.github.matthewjones372.proofload.export

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The encoding is written here on purpose.
 *
 * HdrHistogram would write its own log format in one call, and putting it on
 * this module's main classpath would put it on the classpath of everyone who
 * wanted one file out of a run — for `Deflater` and `Base64`, both of which
 * are in the JDK. It is on the *test* classpath instead, where it is the
 * oracle: its reader reads this module's output back, which is a stronger
 * check than sharing an implementation would have been.
 */
class NoThirdPartyDependenciesTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "proofload-core")

    @Test
    fun `the main runtime classpath is proofload-core and the standard library`() {
        val raw = System.getProperty("proofload.export.runtimeClasspath")
        withClue("the build must pass -Dproofload.export.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("proofload-export grew a dependency: $unexpected") { unexpected.shouldBeEmpty() }
    }
}
