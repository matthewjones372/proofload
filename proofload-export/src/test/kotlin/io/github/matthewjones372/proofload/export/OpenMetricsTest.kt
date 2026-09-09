package io.github.matthewjones372.proofload.export

import io.github.matthewjones372.proofload.Histogram
import io.github.matthewjones372.proofload.Machine
import io.github.matthewjones372.proofload.Outcome
import io.github.matthewjones372.proofload.RunResult
import io.github.matthewjones372.proofload.Said
import io.github.matthewjones372.proofload.StepStats
import io.github.matthewjones372.proofload.Threw
import io.github.matthewjones372.proofload.Timing
import io.github.matthewjones372.proofload.timing
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

class OpenMetricsTest {

    private val exposed = Fixture.run.openMetrics(run = "the-run")

    @Test
    fun `the exposition matches its golden`() {
        exposed matches "run.openmetrics"
    }

    @Test
    fun `buckets are cumulative and end in the infinity the format asks for`() {
        val counts = exposed.lines()
            .filter { it.startsWith("proofload_latency_seconds_bucket") && it.contains("""outcome="ok"""") }
            .filter { it.contains("""clock="service"""") }
            .map { it.substringAfterLast(' ').toLong() }

        withClue(counts.toString()) {
            counts shouldBe counts.sorted()
            counts.last() shouldBe Fixture.run["pay"].ok.serviceTime.count
        }
        exposed shouldContain """le="+Inf"} 4"""
    }

    @Test
    fun `two reasons are two series, counted apart`() {
        exposed shouldContain """proofload_failures_total{run="the-run",step="pay",reason="ConnectException"} 1"""
        exposed shouldContain """proofload_failures_total{run="the-run",step="pay",reason="status 503"} 2"""
    }

    @Test
    fun `the injector's own honesty travels with the numbers`() {
        withClue("a reader who has the latency and not these two is missing the caveat on them") {
            exposed shouldContain "# TYPE proofload_behind_seconds histogram"
            exposed shouldContain "# TYPE proofload_hiccups_seconds histogram"
        }
    }

    @Test
    fun `no judgement leaves the report`() {
        withClue("a series meaning 'this might be noise' is one that gets alerted on as though it were not") {
            listOf("verdict", "goal", "cannot", "steady", "resolution", "interval").forEach {
                exposed.lowercase() shouldNotContain "proofload_$it"
            }
        }
    }

    @Test
    fun `there is no sum, because nothing here adds latencies up`() {
        exposed shouldNotContain "_sum"
    }

    @Test
    fun `a boundary is printed exactly, so no sample lands in a bucket it was not counted in`() {
        val top = Fixture.run["pay"].ok.serviceTime.distribution.first().upperBound.inWholeNanoseconds

        withClue("$top ns as seconds, in full and without an exponent") {
            exposed shouldContain """le="${java.math.BigDecimal(top).movePointLeft(9).stripTrailingZeros()
                .toPlainString()}""""
        }
    }

    @Test
    fun `a reason with a quote in it cannot end its own label`() {
        val awkward = Fixture.run.let { run ->
            run.copy(
                steps = mapOf(
                    "pay" to run["pay"].copy(
                        failed = run["pay"].failed.copy(reasons = mapOf(Said("""said "no"""") to 1L)),
                    ),
                ),
            )
        }

        awkward.openMetrics(run = "the-run") shouldContain """reason="said \"no\""}"""
    }

    @Test
    fun `the exposition ends where the format says it ends`() {
        exposed shouldEndWith "# EOF\n"
    }

    @Test
    fun `a run that measured nothing writes a machine and no series it did not take`() {
        val nothing =
            RunResult(startedAt = Instant.parse("2026-09-02T09:00:00Z"), steps = emptyMap(), behind = Timing.none)

        val bare = nothing.openMetrics(run = "empty")

        bare shouldNotContain "proofload_latency_seconds"
        bare shouldNotContain "proofload_behind_seconds"
        bare shouldContain "proofload_machine_info"
    }

    @Test
    fun `it is written where it was asked for`(@TempDir dir: Path) {
        val written = Fixture.run.writeOpenMetrics(dir.resolve("under/run.openmetrics"), run = "the-run")

        Files.readString(written) shouldBe exposed
    }

    private object Fixture {
        private val here = Machine(cores = 4, jdk = "21.0.2+13", os = "Linux", arch = "aarch64")

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
            machine = here,
        )
    }
}
