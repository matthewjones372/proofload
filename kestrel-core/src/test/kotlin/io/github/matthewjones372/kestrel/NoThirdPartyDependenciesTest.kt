package io.github.matthewjones372.kestrel

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What this module is allowed to put on a consumer's classpath, stated as a
 * test rather than promised in a document.
 *
 * A scenario is a value, so core needs no HTTP client, no coroutine engine and
 * no reporting library. The moment one arrives here, every consumer of every
 * other module inherits it — which is the whole reason the leaf modules exist.
 */
class NoThirdPartyDependenciesTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-")

    @Test
    fun `the main runtime classpath is the Kotlin standard library, and nothing else`() {
        val raw = System.getProperty("kestrel.core.runtimeClasspath")
        withClue("the build must pass -Dkestrel.core.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("kestrel-core must stay dependency-free, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
