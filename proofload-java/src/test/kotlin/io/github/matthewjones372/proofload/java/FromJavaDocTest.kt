package io.github.matthewjones372.proofload.java

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * A snippet nobody compiles is a snippet that rots, so every Java line on
 * `docs/from-java.md` has to be a line of the source set the build compiles.
 */
class FromJavaDocTest {

    private val repoRoot: File
        get() {
            val root = System.getProperty("proofload.repoRoot")
            withClue("the build must pass -Dproofload.repoRoot; see build.gradle.kts") { root.shouldNotBeNull() }
            return File(root!!)
        }

    private val gate = "examples-java/src/main/java/io/github/matthewjones372/proofload/examples/java/Checkout.java"

    private fun javaSnippetLines(): List<String> =
        repoRoot.resolve("docs/from-java.md").readText()
            .split("```java")
            .drop(1)
            .flatMap { it.substringBefore("```").lines() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    @Test
    fun `every Java line on the page is a line of the source set the build compiles`() {
        val compiled = repoRoot.resolve(gate).readLines().map { it.trim() }.toSet()

        val invented = javaSnippetLines().filterNot { it in compiled }

        withClue("docs/from-java.md has Java that is in no compiled source: $invented") {
            invented.shouldBeEmpty()
        }
    }
}
