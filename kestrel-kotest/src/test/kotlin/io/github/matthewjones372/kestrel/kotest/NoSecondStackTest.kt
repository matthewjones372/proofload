package io.github.matthewjones372.kestrel.kotest

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The point of this module, stated as a test: wiring a second framework needs
 * nothing from the first. A `kestrel-junit5` on this classpath would mean core
 * was never framework-agnostic.
 */
class NoSecondStackTest {

    // Kotest itself is compileOnly: a consumer of this module already has it,
    // and its framework engine brings twenty-odd jars nobody asked a load
    // testing library to choose.
    private val allowed = listOf("kotlin-stdlib", "annotations-", "kestrel-core", "kestrel-engine")

    @Test
    fun `the main runtime classpath is kestrel and nothing else, Kotest included`() {
        val raw = System.getProperty("kestrel.kotest.runtimeClasspath")
        withClue("the build must pass -Dkestrel.kotest.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val entries = raw!!.split(File.pathSeparator).filter { it.isNotBlank() }

        withClue("kestrel-kotest grew a dependency") {
            entries.filterNot { entry -> allowed.any { entry.startsWith(it) } }.shouldBeEmpty()
        }
        withClue("this module must not need the JUnit 5 one to work") {
            entries.any { it.startsWith("kestrel-junit5") } shouldBe false
        }
    }
}
