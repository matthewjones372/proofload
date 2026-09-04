package io.github.matthewjones372.kestrel.arbs

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
 * A generator here is arithmetic over a user's number, so nothing needs to
 * arrive with it. kotest's `Arb` is the near miss: it draws the same kind of
 * thing and is biased towards edge cases because it is hunting bugs, and a
 * project that wanted shaped keys should not inherit a property-testing
 * framework for them. The adapter for callers who want it anyway is a module
 * of its own.
 */
class NoThirdPartyDependenciesTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "kestrel-core")

    private fun classpath(): List<String> {
        val raw = System.getProperty("kestrel.arbs.runtimeClasspath")
        withClue("the build must pass -Dkestrel.arbs.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }
        return raw!!.split(File.pathSeparator).filter { it.isNotBlank() }
    }

    @Test
    fun `the main runtime classpath is the Kotlin standard library and kestrel-core, and nothing else`() {
        val unexpected = classpath().filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("kestrel-arbs may depend on kestrel-core and the JDK only, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }

    @Test
    fun `no property-testing library came with it`() {
        val libraries = listOf("kotest-property", "jqwik", "junit-quickcheck", "datafaker", "javafaker")
        val properties = classpath().filter { entry -> libraries.any { entry.startsWith(it) } }

        withClue("a generator here is arithmetic, and this arrived instead: $properties") {
            properties.shouldBeEmpty()
        }
    }

    @Test
    fun `kestrel-core is on the runtime classpath`() {
        val core = classpath().filter { it.startsWith("kestrel-core") }

        withClue("kestrel-arbs fills core's sessions, so core must be exported: ${classpath()}") {
            core.shouldNotBeEmpty()
        }
    }
}
