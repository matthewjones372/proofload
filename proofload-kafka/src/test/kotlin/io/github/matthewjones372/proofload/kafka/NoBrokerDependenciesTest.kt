package io.github.matthewjones372.proofload.kafka

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * This module carries `kafka-clients` on purpose, so the claim worth keeping is
 * which one, and what did not come with it.
 *
 * No schema registry: `io.confluent:kafka-avro-serializer` is not on Maven
 * Central, so depending on it would force a `packages.confluent.io`
 * declaration on every consumer of a published module and break the smoke
 * project, which resolves from `mavenCentral()` on purpose. No broker either,
 * embedded or containerised: whether a cluster is sized right needs the
 * caller's cluster, and whether this adapter is right needs no broker at all.
 */
class NoBrokerDependenciesTest {

    private val entries: List<String>
        get() {
            val raw = System.getProperty("proofload.kafka.runtimeClasspath")
            withClue("the build must pass -Dproofload.kafka.runtimeClasspath; see build.gradle.kts") {
                raw.shouldNotBeNull()
            }
            return raw!!.split(File.pathSeparator).filter { it.isNotBlank() }
        }

    @Test
    fun `no broker, embedded or otherwise`() {
        val brokers = entries.filter { entry ->
            listOf("kafka_2", "kafka-server", "zookeeper", "testcontainers", "curator").any { entry.startsWith(it) }
        }

        withClue("this module's own tests need none, and a consumer of it needs none: $brokers") {
            brokers.shouldBeEmpty()
        }
    }

    @Test
    fun `no schema registry, and no serializer of this tool's own`() {
        val registry = entries.filter { it.startsWith("kafka-avro") || it.startsWith("kafka-schema-registry") }

        withClue("not on Maven Central, so it would break the smoke project: $registry") { registry.shouldBeEmpty() }
    }

    @Test
    fun `no registry on the test classpath either, so the stub is the JDK's own server`() {
        val testing = System.getProperty("java.class.path").split(File.pathSeparator)
            .map { File(it).name }
            .filter { it.startsWith("kafka-avro") || it.startsWith("kafka-schema-registry") }

        withClue("what stands in for a registry here is com.sun.net.httpserver: $testing") {
            testing.shouldBeEmpty()
        }
    }

    @Test
    fun `what is here is proofload-core, kafka-clients and what kafka-clients itself brings`() {
        val allowed = listOf(
            "kotlin-stdlib", "annotations-", "proofload-core", "kafka-clients",
            // kafka-clients' own compression codecs and its logging facade,
            // which arrive with it and are not a choice this module made.
            "zstd-jni", "lz4-java", "snappy-java", "slf4j-api",
        )

        val unexpected = entries.filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("proofload-kafka grew a dependency: $unexpected") { unexpected.shouldBeEmpty() }
    }
}
