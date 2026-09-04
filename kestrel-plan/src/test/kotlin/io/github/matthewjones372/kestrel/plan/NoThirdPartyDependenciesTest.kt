package io.github.matthewjones372.kestrel.plan

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What this module is allowed to put on a consumer's classpath, stated as a
 * test rather than promised in a document.
 *
 * A plan lowers into HTTP steps, so this module carries `kestrel-http` and the
 * JDK's own client with it, plus the one parser that reads a plan. It carries
 * no second client, no second parser and no server stack.
 */
class NoThirdPartyDependenciesTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "kestrel-core", "kestrel-http", "snakeyaml-engine")

    @Test
    fun `the main runtime classpath is the standard library, kestrel and one yaml parser`() {
        val raw = System.getProperty("kestrel.plan.runtimeClasspath")
        withClue("the build must pass -Dkestrel.plan.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("kestrel-plan may carry kestrel-http and one parser, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
