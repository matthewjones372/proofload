package io.github.matthewjones372.kestrel.http

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
 * The client here is `java.net.http`, which is the JDK, so a project that takes
 * kestrel-http takes kestrel-core and no client stack. A Ktor or OkHttp module
 * belongs beside this one, not inside it.
 */
class NoThirdPartyDependenciesTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "kestrel-core")

    private fun classpath(): List<String> {
        val raw = System.getProperty("kestrel.http.runtimeClasspath")
        withClue("the build must pass -Dkestrel.http.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }
        return raw!!.split(File.pathSeparator).filter { it.isNotBlank() }
    }

    @Test
    fun `the main runtime classpath is the Kotlin standard library and kestrel-core, and nothing else`() {
        val unexpected = classpath().filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("kestrel-http may depend on kestrel-core and the JDK only, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }

    @Test
    fun `kestrel-core is on the runtime classpath`() {
        val core = classpath().filter { it.startsWith("kestrel-core") }

        withClue("kestrel-http is core's HTTP adapter, so core must be exported: ${classpath()}") {
            core.shouldNotBeEmpty()
        }
    }
}
