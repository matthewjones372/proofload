package io.github.matthewjones372.proofload.jdbc

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
 * `java.sql` and `javax.sql` are the JDK, so a project that takes proofload-jdbc
 * takes proofload-core and no database stack. The driver is the caller's, as the
 * `DataSource` is: a pool built here would have different limits from the one
 * their service runs, and the pool is the thing under test as often as the
 * database.
 */
class NoThirdPartyDependenciesTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "proofload-core")

    private fun classpath(): List<String> {
        val raw = System.getProperty("proofload.jdbc.runtimeClasspath")
        withClue("the build must pass -Dproofload.jdbc.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }
        return raw!!.split(File.pathSeparator).filter { it.isNotBlank() }
    }

    @Test
    fun `the main runtime classpath is the Kotlin standard library and proofload-core, and nothing else`() {
        val unexpected = classpath().filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("proofload-jdbc may depend on proofload-core and the JDK only, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }

    @Test
    fun `no driver came with it`() {
        val drivers = classpath().filter { entry ->
            listOf("h2", "postgresql", "mysql", "mariadb", "ojdbc", "HikariCP").any { entry.startsWith(it) }
        }

        withClue("a driver here is a driver in every consumer's process: $drivers") {
            drivers.shouldBeEmpty()
        }
    }

    @Test
    fun `proofload-core is on the runtime classpath`() {
        val core = classpath().filter { it.startsWith("proofload-core") }

        withClue("proofload-jdbc is core's database adapter, so core must be exported: ${classpath()}") {
            core.shouldNotBeEmpty()
        }
    }
}
