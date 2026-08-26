package io.github.matthewjones372.kestrel.baseline

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

    private val allowed = listOf("kotlin-stdlib", "annotations-", "kestrel-core")

    @Test
    fun `the main runtime classpath is kestrel-core and the standard library`() {
        val raw = System.getProperty("kestrel.baseline.runtimeClasspath")
        withClue("the build must pass -Dkestrel.baseline.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("kestrel-baseline grew a dependency: $unexpected") { unexpected.shouldBeEmpty() }
    }
}
