package io.github.matthewjones372.proofload.examples

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The smoke project is a build of its own, so no task in this build notices
 * when a module is published that the smoke project never asks a repository
 * for — and a published module nobody installs is the failure a release is
 * meant to find.
 */
class SmokeProjectTest {

    /** The subprojects the root build keeps out of `publishedModules`. */
    private val notPublished = setOf("examples", "examples-java", "examples-scala", "benchmarks")

    private fun read(property: String): String {
        val path = System.getProperty(property)
        withClue("the build must pass -D$property; see examples/build.gradle.kts") {
            path.shouldNotBeNull()
        }
        return File(path!!).readText()
    }

    private fun publishedModules(): List<String> =
        read("proofload.settings")
            .substringAfter("include(")
            .substringBefore(")")
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .flatMap { Regex("\"([\\w-]+)\"").findAll(it) }
            .map { it.groupValues[1] }
            .filterNot { it in notPublished }
            .toList()

    @Test
    fun `the smoke project asks a repository for every published module`() {
        val smoke = read("proofload.smokeBuild")
        val modules = publishedModules()

        withClue("settings.gradle.kts should name the modules that get published") {
            modules shouldContain "proofload-core"
        }

        val missing = modules.filterNot { smoke.contains("io.github.matthewjones372:$it:") }
        withClue("smoke/build.gradle.kts resolves no coordinate for: $missing") {
            missing.shouldBeEmpty()
        }
    }
}
