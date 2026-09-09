package io.github.matthewjones372.proofload.pelican

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Pelican is the point of this module, so pelican-core is allowed. Pekko is
 * not: an actor system inside a load generator is a second scheduler for this
 * tool to measure, and `pelican-client-pekko` exists precisely so nobody has
 * to take it.
 */
class NoPekkoTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "proofload-core", "pelican-core")

    @Test
    fun `the main runtime classpath is proofload, pelican-core, and no actor system`() {
        val raw = System.getProperty("proofload.pelican.runtimeClasspath")
        withClue("the build must pass -Dproofload.pelican.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val entries = raw!!.split(File.pathSeparator).filter { it.isNotBlank() }

        withClue("proofload-pelican grew a dependency") {
            entries.filterNot { entry -> allowed.any { entry.startsWith(it) } }.shouldBeEmpty()
        }
        withClue("no part of Pekko may reach a load test") {
            entries.any { it.contains("pekko") || it.contains("akka") } shouldBe false
        }
    }
}
