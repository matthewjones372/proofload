package io.github.matthewjones372.kestrel.contract

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What this module is allowed to put on a consumer's classpath, stated as a
 * test rather than promised in a document.
 *
 * A contract reader carries the contract library and the plan it writes, and no
 * server stack: nothing here runs while anything is being measured, in the way
 * `kestrel-record` does not either. No Pekko, which is `pelican-client-pekko`'s
 * and would be a second scheduler inside a load generator.
 */
class NoThirdPartyDependenciesTest {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "kestrel-core",
        "kestrel-http",
        "kestrel-plan",
        // Arrives with `kestrel-plan`, which draws a value per user. Pure
        // Kotlin over core, so no third-party jar comes with it.
        "kestrel-arbs",
        "kestrel-openapi",
        "pelican-core",
        "snakeyaml-engine",
    )

    @Test
    fun `the main runtime classpath is kestrel, pelican-core and the one parser`() {
        val raw = System.getProperty("kestrel.contract.runtimeClasspath")
        withClue("the build must pass -Dkestrel.contract.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("kestrel-contract carries no server stack and no actor system, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
