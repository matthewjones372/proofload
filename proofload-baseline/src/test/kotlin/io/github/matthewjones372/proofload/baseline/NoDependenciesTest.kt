package io.github.matthewjones372.proofload.baseline

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The format is written and read here on purpose. A baseline that needed a JSON
 * library would put one on the classpath of everyone who wanted to compare two
 * runs, which is the whole layering argument in miniature.
 */
class NoDependenciesTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "proofload-core")

    @Test
    fun `the main runtime classpath is proofload-core and the standard library`() {
        val raw = System.getProperty("proofload.baseline.runtimeClasspath")
        withClue("the build must pass -Dproofload.baseline.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("proofload-baseline grew a dependency: $unexpected") { unexpected.shouldBeEmpty() }
    }
}
