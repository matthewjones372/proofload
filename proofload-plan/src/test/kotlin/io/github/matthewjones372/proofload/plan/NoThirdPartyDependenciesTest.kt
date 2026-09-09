package io.github.matthewjones372.proofload.plan

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What this module is allowed to put on a consumer's classpath, stated as a
 * test rather than promised in a document.
 *
 * A plan lowers into HTTP steps, so this module carries `proofload-http` and the
 * JDK's own client with it, plus the one parser that reads a plan. It carries
 * no second client, no second parser and no server stack.
 */
class NoThirdPartyDependenciesTest {

    private val allowed =
        listOf(
            "kotlin-stdlib",
            "annotations-",
            "proofload-core",
            "proofload-http",
            "proofload-arbs",
            "snakeyaml-engine",
        )

    @Test
    fun `the main runtime classpath is the standard library, proofload and one yaml parser`() {
        val raw = System.getProperty("proofload.plan.runtimeClasspath")
        withClue("the build must pass -Dproofload.plan.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("proofload-plan may carry proofload-http and one parser, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
