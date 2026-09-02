package io.github.matthewjones372.kestrel.otel

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * This module carries a dependency on purpose — the OpenTelemetry SDK, the way
 * `kestrel-pelican` carries `pelican-core` — so the claim worth keeping is not
 * that it has none. It is which one.
 *
 * OTLP over HTTP rather than gRPC, so what arrives is the SDK, its exporter and
 * their own transitive set, and not a gRPC runtime with Netty under it. That is
 * the difference between a consumer taking a metrics exporter and a consumer
 * taking a second networking stack into a process whose whole job is to measure
 * the first one.
 */
class NoGrpcStackTest {

    private val entries: List<String>
        get() {
            val raw = System.getProperty("kestrel.otel.runtimeClasspath")
            withClue("the build must pass -Dkestrel.otel.runtimeClasspath; see build.gradle.kts") {
                raw.shouldNotBeNull()
            }
            return raw!!.split(File.pathSeparator).filter { it.isNotBlank() }
        }

    @Test
    fun `no gRPC runtime and no Netty reached the classpath`() {
        val networking = entries.filter { entry ->
            listOf("grpc-", "netty", "okhttp").any { entry.startsWith(it) }
        }

        withClue("a load test's own process is the one thing it cannot afford to make busier: $networking") {
            networking.shouldBeEmpty()
        }
    }

    @Test
    fun `what is here is the SDK, its exporter over the JDK client, and kestrel-core`() {
        withClue(entries.toString()) {
            entries.any { it.startsWith("kestrel-core") } shouldBe true
            entries.any { it.startsWith("opentelemetry-sdk-metrics") } shouldBe true
            entries.any { it.startsWith("opentelemetry-exporter-otlp") } shouldBe true
            entries.any { it.startsWith("opentelemetry-exporter-sender-jdk") } shouldBe true
        }
    }

    @Test
    fun `nothing outside OpenTelemetry, Kotlin and kestrel came with them`() {
        val allowed = listOf("kotlin-stdlib", "annotations-", "kestrel-", "opentelemetry-")

        val unexpected = entries.filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("kestrel-otel grew a dependency outside the SDK it exists to carry: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
