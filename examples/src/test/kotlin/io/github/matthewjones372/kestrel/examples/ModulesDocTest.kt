package io.github.matthewjones372.kestrel.examples

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `AGENTS.md` says every dependency claim in `docs/modules.md` is a test. Each
 * module makes its own claim about its own classpath; this is what stops the
 * document from describing a build that no longer exists — a module nobody
 * wrote down, or a test named after it was renamed.
 */
class ModulesDocTest {

    // `.scala` as well as `.kt`: a module's claim about its own classpath is
    // asserted in the language that module is written in.
    private val testPath = Regex("""[\w.-]+/src/test/[\w./-]+Test\.(kt|scala)""")

    private val repoRoot: File
        get() {
            val root = System.getProperty("kestrel.repoRoot")
            withClue("the build must pass -Dkestrel.repoRoot; see examples/build.gradle.kts") {
                root.shouldNotBeNull()
            }
            return File(root!!)
        }

    private fun documentation(): String = repoRoot.resolve("docs/modules.md").readText()

    private fun includedModules(): List<String> {
        val block = repoRoot.resolve("settings.gradle.kts").readText()
            .substringAfter("include(")
            .substringBefore(")")
        return Regex("\"([^\"]+)\"").findAll(block).map { it.groupValues[1] }.toList()
    }

    private fun testsNamed(): Set<String> = testPath.findAll(documentation()).map { it.value }.toSet()

    @Test
    fun `every module the build includes is described`() {
        val undescribed = includedModules().filterNot { documentation().contains("`$it`") }

        withClue("docs/modules.md does not mention: $undescribed") { undescribed.shouldBeEmpty() }
    }

    @Test
    fun `every published module names the test that proves what it depends on`() {
        val published = includedModules() - NOT_PUBLISHED
        val unproven = published.filterNot { module -> testsNamed().any { it.startsWith("$module/") } }

        withClue("docs/modules.md claims a classpath it does not name a test for: $unproven") {
            unproven.shouldBeEmpty()
        }
    }

    @Test
    fun `every test the documentation names is there`() {
        val missing = testsNamed().filterNot { repoRoot.resolve(it).isFile }

        withClue("docs/modules.md links to tests that do not exist: $missing") { missing.shouldBeEmpty() }
    }

    private companion object {

        /** The subprojects the root build keeps out of `publishedModules`. */
        val NOT_PUBLISHED = setOf("examples", "examples-java", "examples-scala", "benchmarks")
    }
}
