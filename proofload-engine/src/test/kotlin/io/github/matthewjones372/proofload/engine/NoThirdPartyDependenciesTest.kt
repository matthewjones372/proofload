package io.github.matthewjones372.proofload.engine

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What this module is allowed to put on a consumer's classpath, stated as a
 * test rather than promised in a document.
 *
 * The engine runs users on virtual threads and paces them on a
 * `ScheduledExecutorService`, both of which the JDK already carries. A client,
 * a coroutine engine or a metrics library arriving here would be inherited by
 * everyone who runs a simulation.
 */
class NoThirdPartyDependenciesTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "proofload-core")

    @Test
    fun `the main runtime classpath is the Kotlin standard library and proofload-core, and nothing else`() {
        val raw = System.getProperty("proofload.engine.runtimeClasspath")
        withClue("the build must pass -Dproofload.engine.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("proofload-engine must carry only proofload-core, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
