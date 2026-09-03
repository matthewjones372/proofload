package io.github.matthewjones372.kestrel.otel

import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.kestrel.Snapshot
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.opentelemetry.sdk.metrics.data.AggregationTemporality
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.metrics.data.MetricDataType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A run that says what it is doing while it is doing it, and what becomes of a
 * two-hour soak whose collector goes away at minute one.
 */
class LiveOtlpTest {

    private lateinit var collector: HttpServer

    private val received = ConcurrentLinkedQueue<Int>()

    @BeforeEach
    fun start() {
        collector = HttpServer.create(InetSocketAddress(0), 0)
        collector.createContext("/v1/metrics") { exchange ->
            received += exchange.requestBody.use { it.readBytes() }.size
            exchange.sendResponseHeaders(200, 0L)
            exchange.responseBody.close()
        }
        collector.start()
    }

    @AfterEach
    fun stop() = collector.stop(0)

    private val endpoint: String get() = "http://localhost:${collector.address.port}/v1/metrics"

    private class Captured : Collector {

        override val named: String = "the-test"

        val pushes = mutableListOf<List<MetricData>>()

        val closes = mutableListOf<Unit>()

        override fun send(metrics: List<MetricData>): Boolean {
            pushes += metrics
            return true
        }

        override fun close() {
            closes += Unit
        }
    }

    private fun snapshot(
        departed: Long = 0L,
        inFlight: Long = 0L,
        behind: Duration = Duration.ZERO,
        requests: Long = 0L,
        failed: Long = 0L,
        ended: Boolean = false,
    ): Snapshot = Snapshot(
        departed = departed,
        inFlight = inFlight,
        behind = behind,
        ended = ended,
        requests = requests,
        failed = failed,
    )

    @Test
    fun `a tick carries what the scheduler knows, and nothing it would read a histogram for`() {
        val captured = Captured()

        Live("the-run", captured).tick(
            10.seconds,
            snapshot(departed = 500, inFlight = 12, behind = 40.milliseconds, requests = 480, failed = 3),
        )

        val names = captured.pushes.single().map { it.name }
        withClue("$names") {
            names shouldContainAll listOf(
                "kestrel.departed",
                "kestrel.in_flight",
                "kestrel.requests",
                "kestrel.failures",
                "kestrel.behind.last",
            )
        }
        withClue("a live percentile needs the histograms under the writers, which is the lock this avoids") {
            names.none { it.contains("latency") } shouldBe true
        }
    }

    @Test
    fun `the counters are what happened since the last tick, not the run so far counted again`() {
        val captured = Captured()
        val live = Live("the-run", captured)

        live.tick(5.seconds, snapshot(requests = 100, failed = 2))
        live.tick(10.seconds, snapshot(requests = 260, failed = 5))

        captured.pushes.map { it.longValue("kestrel.requests") } shouldContainExactly listOf(100L, 160L)
        captured.pushes.map { it.longValue("kestrel.failures") } shouldContainExactly listOf(2L, 3L)
    }

    @Test
    fun `a counter is a delta over the window it names, as the finished run's is`() {
        val captured = Captured()

        Live("the-run", captured).tick(5.seconds, snapshot(requests = 100))

        captured.pushes.single().filter { it.type == MetricDataType.LONG_SUM }.forEach { metric ->
            withClue(metric.name) {
                metric.longSumData.aggregationTemporality shouldBe AggregationTemporality.DELTA
            }
        }
    }

    @Test
    fun `a gauge is what the scheduler had at that instant`() {
        val captured = Captured()

        Live("the-run", captured).tick(10.seconds, snapshot(departed = 500, inFlight = 12, behind = 40.milliseconds))

        val push = captured.pushes.single()
        push.longValue("kestrel.departed") shouldBe 500L
        push.longValue("kestrel.in_flight") shouldBe 12L
        push.single { it.name == "kestrel.behind.last" }.doubleGaugeData.points.single().value shouldBe 0.04
    }

    @Test
    fun `nothing is pushed after the run's last tick`() {
        val captured = Captured()
        val live = Live("the-run", captured)

        live.tick(5.seconds, snapshot(requests = 10, ended = true))
        live.tick(6.seconds, snapshot(requests = 20))

        captured.pushes.size shouldBe 1
        withClue("the exporter is let go with the run rather than left holding a connection") {
            captured.closes.size shouldBe 1
        }
    }

    @Test
    fun `a collector that is not there is one warning, not a run that died at minute one`() {
        val nowhere = "http://localhost:${closedPort()}/v1/metrics"
        val live = otlpEvery(1.seconds, to = nowhere, run = "the-run", within = 1.seconds)

        val warnings = onStandardError {
            live.tick(1.seconds, snapshot(requests = 10))
            live.tick(2.seconds, snapshot(requests = 20))
            live.tick(3.seconds, snapshot(requests = 30, ended = true))
        }

        withClue("$warnings") {
            warnings.size shouldBe 1
            warnings.single() shouldContain nowhere
        }
    }

    @Test
    fun `a collector that is there is pushed to no more often than the interval asked for`() {
        val live = otlpEvery(5.seconds, to = endpoint, run = "the-run")

        (1..10).forEach { second -> live.tick(second.seconds, snapshot(requests = second * 10L)) }
        live.tick(11.seconds, snapshot(requests = 110, ended = true))

        withClue("$received") { received.size shouldBe 3 }
        withClue("an empty body is not a push") { received.all { it > 0 } shouldBe true }
    }

    private fun List<MetricData>.longValue(name: String): Long = single { it.name == name }.let { metric ->
        if (metric.type == MetricDataType.LONG_SUM) {
            metric.longSumData.points.single().value
        } else {
            metric.longGaugeData.points.single().value
        }
    }

    private fun onStandardError(block: () -> Unit): List<String> {
        val captured = ByteArrayOutputStream()
        val original = System.err
        System.setErr(PrintStream(captured, true, Charsets.UTF_8))
        try {
            block()
        } finally {
            System.setErr(original)
        }
        // Kestrel's own lines only: the SDK logs its own severe record for the
        // same refusal, which is the "the exporter logged why" this warning
        // points at rather than a second warning of ours.
        return captured.toString(Charsets.UTF_8).lines().filter { it.startsWith("kestrel:") }
    }

    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }
}
