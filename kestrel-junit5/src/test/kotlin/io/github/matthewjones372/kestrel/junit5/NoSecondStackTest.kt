package io.github.matthewjones372.kestrel.junit5

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * JUnit is this module's whole point, so it is allowed. A second way to run a
 * load test is not: the moment a client or a reporting library appears here,
 * every load test in every consumer inherits it.
 */
class NoSecondStackTest {

    private val allowed =
        listOf("kotlin-stdlib", "annotations-", "kestrel-core", "kestrel-engine", "junit-", "opentest4j", "apiguardian")

    @Test
    fun `the main runtime classpath is kestrel, JUnit, and nothing else`() {
        val raw = System.getProperty("kestrel.junit5.runtimeClasspath")
        withClue("the build must pass -Dkestrel.junit5.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val entries = raw!!.split(File.pathSeparator).filter { it.isNotBlank() }
        val unexpected = entries.filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("kestrel-junit5 grew a dependency: $unexpected") { unexpected.shouldBeEmpty() }
        withClue("the engine is what runs a simulation, so it must be here") {
            entries.map { it.substringBefore("-0") } shouldContain "kestrel-engine"
        }
    }
}
