package io.github.matthewjones372.proofload.otel

import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.proofload.Histogram
import io.github.matthewjones372.proofload.Machine
import io.github.matthewjones372.proofload.Outcome
import io.github.matthewjones372.proofload.RunResult
import io.github.matthewjones372.proofload.Said
import io.github.matthewjones372.proofload.StepStats
import io.github.matthewjones372.proofload.Threw
import io.github.matthewjones372.proofload.Timing
import io.github.matthewjones372.proofload.export.openMetrics
import io.github.matthewjones372.proofload.timing
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.opentelemetry.sdk.metrics.data.AggregationTemporality
import io.opentelemetry.sdk.metrics.data.HistogramPointData
import io.opentelemetry.sdk.metrics.data.MetricDataType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What a collector is handed, and what happens when there is not one.
 *
 * The second is the half worth testing hardest: a load test that dies because
 * an observability backend was down is one people stop running, and the
 * measurement it was carrying is the thing that mattered.
 */
class OtlpTest {

    private lateinit var collector: HttpServer

    private val received = ConcurrentLinkedQueue<Pair<String, Int>>()

    @BeforeEach
    fun start() {
        collector = HttpServer.create(InetSocketAddress(0), 0)
        collector.createContext("/v1/metrics") { exchange ->
            val body = exchange.requestBody.use { it.readBytes() }
            received += (exchange.requestHeaders.getFirst("Content-Type") ?: "none") to body.size
            // An empty ExportMetricsServiceResponse, which is what a collector
            // that took everything answers with.
            exchange.sendResponseHeaders(200, 0L)
            exchange.responseBody.close()
        }
        collector.start()
    }

    @AfterEach
    fun stop() = collector.stop(0)

    private val endpoint: String get() = "http://localhost:${collector.address.port}/v1/metrics"

    @Test
    fun `a collector that is there takes them`() {
        Fixture.run.sendOtlp(endpoint, run = "the-run") shouldBe Sent.Accepted

        val (contentType, bytes) = received.single()
        withClue("$contentType, $bytes bytes") {
            contentType shouldContain "protobuf"
            (bytes > 0) shouldBe true
        }
    }

    @Test
    fun `a collector that is not there is a refusal naming it, rather than a run that died`() {
        val nowhere = "http://localhost:${closedPort()}/v1/metrics"

        val sent = Fixture.run.sendOtlp(nowhere, run = "the-run", within = 2.seconds)

        withClue("the measurement is the thing that mattered, and it is still in the caller's hand") {
            sent.shouldBeInstanceOf<Sent.Refused>().why shouldContain nowhere
        }
    }

    @Test
    fun `the boundaries and the counts are this histogram's own, not re-bucketed`() {
        val point = Fixture.run.metricData(run = "the-run")
            .single { it.name == "proofload.latency" && it.histogramData.points.any { p -> p.isOk() } }
            .histogramData.points.first { it.isOk() }

        val mine = Fixture.run["pay"].ok.serviceTime
        withClue("OTel's last bucket is everything above the last boundary, so there is one fewer of them") {
            point.boundaries shouldContainExactly mine.distribution.dropLast(1).map { it.upperBound.asSeconds() }
            point.counts shouldContainExactly mine.distribution.map { it.count }
            point.count shouldBe mine.count
        }
    }

    @Test
    fun `it is a delta over the run's own window, not a counter since the process started`() {
        Fixture.run.metricData(run = "the-run").forEach { metric ->
            withClue(metric.name) {
                when (metric.type) {
                    MetricDataType.HISTOGRAM ->
                        metric.histogramData.aggregationTemporality shouldBe AggregationTemporality.DELTA

                    else -> metric.longSumData.aggregationTemporality shouldBe AggregationTemporality.DELTA
                }
            }
        }
    }

    @Test
    fun `it is never an exponential histogram, whose boundaries are not the ones measured`() {
        val kinds = Fixture.run.metricData(run = "the-run").map { it.type }.distinct()

        withClue("$kinds") { kinds.contains(MetricDataType.EXPONENTIAL_HISTOGRAM) shouldBe false }
    }

    @Test
    fun `two reasons are two points, counted apart`() {
        val failures = Fixture.run.metricData(run = "the-run").single { it.name == "proofload.failures" }

        failures.longSumData.points.map { it.value }.sorted() shouldContainExactly listOf(1L, 2L)
    }

    @Test
    fun `the two exports say the same thing, boundary for boundary`() {
        val exposition = Fixture.run.openMetrics(run = "the-run")
        val point = Fixture.run.metricData(run = "the-run")
            .single { it.name == "proofload.latency" && it.histogramData.points.any { p -> p.isOk() } }
            .histogramData.points.first { it.isOk() }

        point.boundaries.forEach { boundary ->
            val le = java.math.BigDecimal(boundary).setScale(NANO_DIGITS, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString()
            withClue("$boundary as $le") { exposition shouldContain """le="$le"""" }
        }
    }

    @Test
    fun `no judgement leaves the report`() {
        val names = Fixture.run.metricData(run = "the-run").map { it.name }

        withClue("$names") {
            names.none { it.contains("goal") || it.contains("verdict") || it.contains("steady") } shouldBe true
        }
    }

    private fun HistogramPointData.isOk(): Boolean =
        attributes.asMap().entries.any { it.key.key == "outcome" && it.value == "ok" } &&
            attributes.asMap().entries.any { it.key.key == "clock" && it.value == "service" }

    private fun kotlin.time.Duration.asSeconds(): Double = inWholeNanoseconds / 1_000_000_000.0

    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    private companion object {
        const val NANO_DIGITS = 9
    }

    private object Fixture {
        private fun timingOf(vararg micros: Long): Timing =
            Histogram().apply { micros.forEach { record(it.microseconds) } }.timing()

        val run: RunResult = RunResult(
            startedAt = Instant.parse("2026-09-02T09:00:00Z"),
            steps = mapOf(
                "pay" to StepStats(
                    name = "pay",
                    ok = Outcome(timingOf(900, 1_100, 20_000, 21_000), timingOf(950, 1_150, 20_100, 21_100)),
                    failed = Outcome(
                        timingOf(300_000, 310_000, 900_000),
                        timingOf(300_050, 310_050, 900_050),
                        mapOf(Said("status 503") to 2L, Threw("ConnectException") to 1L),
                    ),
                    serviceTime = timingOf(900, 1_100, 20_000, 21_000, 300_000, 310_000, 900_000),
                    responseTime = timingOf(950, 1_150, 20_100, 21_100, 300_050, 310_050, 900_050),
                ),
            ),
            behind = Histogram().apply { repeat(3) { record(120.microseconds) } }.timing(),
            hiccups = Histogram().apply { repeat(2) { record(4.milliseconds) } }.timing(),
            machine = Machine(cores = 4, jdk = "21.0.2+13", os = "Linux", arch = "aarch64"),
        )
    }
}
