package io.github.matthewjones372.kestrel.java

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What this module is allowed to put on a consumer's classpath, stated as a
 * test rather than promised in a document.
 *
 * A facade that delegates carries the modules whose values it builds and
 * nothing else. A library added here would reach a Java caller who asked only
 * for a readable name.
 */
class NoThirdPartyDependenciesTest {

    private val exported = listOf("kestrel-core", "kestrel-engine", "kestrel-http")

    private val allowed = exported + listOf("kotlin-stdlib", "annotations-")

    private fun classpath(): List<String> {
        val raw = System.getProperty("kestrel.java.runtimeClasspath")
        withClue("the build must pass -Dkestrel.java.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }
        return raw!!.split(File.pathSeparator).filter { it.isNotBlank() }
    }

    @Test
    fun `the main runtime classpath is the modules it delegates to, and nothing else`() {
        val unexpected = classpath().filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("kestrel-java may depend on $exported and the JDK only, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }

    @Test
    fun `every module it hands out values from is exported`() {
        exported.forEach { module ->
            withClue("a Java caller has to be able to name what $module returns: ${classpath()}") {
                classpath().filter { it.startsWith(module) }.shouldNotBeEmpty()
            }
        }
    }
}
