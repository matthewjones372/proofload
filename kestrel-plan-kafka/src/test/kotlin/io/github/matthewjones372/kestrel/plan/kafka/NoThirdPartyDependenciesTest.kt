package io.github.matthewjones372.kestrel.plan.kafka

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * This module is where the plan's stack and Kafka's meet, and the claim worth
 * keeping is that it is the *only* place they do: a project reading a plan of
 * nothing but requests takes `kestrel-plan` and gets none of this.
 */
class NoThirdPartyDependenciesTest {

    private val entries: List<String>
        get() {
            val raw = System.getProperty("kestrel.plan.kafka.runtimeClasspath")
            withClue("the build must pass -Dkestrel.plan.kafka.runtimeClasspath; see build.gradle.kts") {
                raw.shouldNotBeNull()
            }
            return raw!!.split(File.pathSeparator).filter { it.isNotBlank() }
        }

    @Test
    fun `what is here is the plan, Kafka, and what each of them already brought`() {
        val allowed = listOf(
            "kotlin-stdlib", "annotations-",
            "kestrel-core", "kestrel-http", "kestrel-plan", "kestrel-kafka",
            // The plan's parser and Kafka's own codecs and logging facade,
            // which arrive with those two and are not a choice made here.
            "snakeyaml-engine", "kafka-clients", "zstd-jni", "lz4-java", "snappy-java", "slf4j-api",
        )

        val unexpected = entries.filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("kestrel-plan-kafka grew a dependency: $unexpected") { unexpected.shouldBeEmpty() }
    }

    @Test
    fun `the Kafka client is here rather than under the plan, which is the reason this module exists`() {
        withClue("if kafka-clients were reachable from kestrel-plan, this module would be pointless") {
            entries.filter { it.startsWith("kafka-clients") }.shouldNotBeEmpty()
        }
    }
}
