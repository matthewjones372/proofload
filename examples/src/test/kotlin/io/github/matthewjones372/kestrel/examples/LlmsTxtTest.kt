package io.github.matthewjones372.kestrel.examples

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `llms.txt` is read by a caller with one context window and no second chance
 * to check, so a wrong line in it costs that caller a compile error it cannot
 * map back to the right shape. Nothing in it is taken on trust: the
 * coordinates are checked against the build, the links against the tree, and
 * every line of Kotlin against a source `./gradlew build` compiles and runs.
 */
class LlmsTxtTest {

    private val fence = Regex("```kotlin\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)

    private val coordinate = Regex("""io\.github\.matthewjones372:([a-z0-9-]+)""")

    private val link = Regex("""https://github\.com/matthewjones372/kestrel/blob/main/([^)\s#]+)""")

    private val repoRoot: File
        get() {
            val root = System.getProperty("kestrel.repoRoot")
            withClue("the build must pass -Dkestrel.repoRoot; see examples/build.gradle.kts") {
                root.shouldNotBeNull()
            }
            return File(root!!)
        }

    private fun theFile(): File = repoRoot.resolve("llms.txt")

    /** A block declaring dependencies is coordinates, checked as coordinates rather than as code. */
    private fun snippets(): List<String> = fence.findAll(theFile().readText())
        .map { it.groupValues[1] }
        .filterNot { it.contains("dependencies {") }
        .toList()

    /** Blank lines and comments carry no API, so they are not held to appearing anywhere. */
    private fun linesOfKotlin(): List<String> = snippets()
        .flatMap { it.lines() }
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("//") }

    private fun linesTheBuildCompiles(): Set<String> = repoRoot.resolve("examples/src")
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .flatMap { it.readLines() }
        .map { it.trim() }
        .toSet()

    @Test
    fun `it is at the repository root, and short enough to read in one sitting`() {
        withClue("llms.txt belongs at the root, where every other repository keeps it") {
            theFile().isFile shouldBe true
        }

        withClue("a file nobody reads to the end is a file that taught nothing") {
            theFile().readLines().size shouldBeLessThan MAX_LINES
        }
    }

    @Test
    fun `every coordinate it names is a module this build produces`() {
        val settings = repoRoot.resolve("settings.gradle.kts").readText()
        val invented = coordinate.findAll(theFile().readText())
            .map { it.groupValues[1] }
            .filterNot { settings.contains("\"$it\"") }
            .toList()

        withClue("llms.txt names artifacts the build does not make: $invented") { invented.shouldBeEmpty() }
    }

    @Test
    fun `every document it links to is in the tree`() {
        val missing = link.findAll(theFile().readText())
            .map { it.groupValues[1] }
            .filterNot { repoRoot.resolve(it).exists() }
            .toList()

        withClue("llms.txt links to files that are not there: $missing") { missing.shouldBeEmpty() }
    }

    @Test
    fun `every line of Kotlin it shows is a line of Kotlin the build compiles`() {
        val compiled = linesTheBuildCompiles()
        val invented = linesOfKotlin().filterNot { it in compiled }

        withClue("llms.txt shows Kotlin that appears in no tested source: $invented") { invented.shouldBeEmpty() }
    }

    private companion object {
        /** The whole point of the file is that it is read before anything else, so it fits on a screen. */
        const val MAX_LINES = 100
    }
}
