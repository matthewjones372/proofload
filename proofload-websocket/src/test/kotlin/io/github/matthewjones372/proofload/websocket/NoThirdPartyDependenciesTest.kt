package io.github.matthewjones372.proofload.websocket

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
 * The client here is `java.net.http.WebSocket`, which is the JDK, so a project
 * that takes proofload-websocket takes proofload-core and no client stack. A Netty
 * or Ktor module belongs beside this one, not inside it.
 */
class NoThirdPartyDependenciesTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "proofload-core")

    private fun classpath(): List<String> {
        val raw = System.getProperty("proofload.websocket.runtimeClasspath")
        withClue("the build must pass -Dproofload.websocket.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }
        return raw!!.split(File.pathSeparator).filter { it.isNotBlank() }
    }

    @Test
    fun `the main runtime classpath is the Kotlin standard library and proofload-core, and nothing else`() {
        val unexpected = classpath().filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("proofload-websocket may depend on proofload-core and the JDK only, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }

    @Test
    fun `proofload-core is on the runtime classpath`() {
        val core = classpath().filter { it.startsWith("proofload-core") }

        withClue("proofload-websocket is core's WebSocket adapter, so core must be exported: ${classpath()}") {
            core.shouldNotBeEmpty()
        }
    }
}
