package io.github.matthewjones372.kestrel.benchmarks

import io.github.matthewjones372.kestrel.Histogram
import io.github.matthewjones372.kestrel.Outcome
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.StepStats
import io.github.matthewjones372.kestrel.Threw
import io.github.matthewjones372.kestrel.Timing
import io.github.matthewjones372.kestrel.timing
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

/**
 * What the sweep writes, from rows built rather than measured: how long
 * anything takes on the machine running this is not something a test can claim.
 */
class CeilingReportTest {

    @Test
    fun `the report carries a table for each sweep`() {
        val page = report(
            withoutASocket = listOf(row(1_000, behind = 20.microseconds)),
            overASocket = listOf(row(500, behind = 40.microseconds, served = 300.microseconds)),
        )

        withClue(page) {
            page.contains("## Over a socket") shouldBe true
            page.contains("## Without a socket") shouldBe true
        }
    }

    @Test
    fun `each ceiling is the highest rate whose median departure stayed inside the budget`() {
        val page = report(
            withoutASocket = listOf(
                row(1_000, behind = 20.microseconds),
                row(10_000, behind = 30.microseconds),
                row(25_000, behind = 4.milliseconds),
            ),
            overASocket = listOf(
                row(500, behind = 40.microseconds, served = 300.microseconds),
                row(1_000, behind = 9.milliseconds, served = 8.milliseconds),
            ),
        )

        withClue(page) {
            page.contains("Ceiling without a socket: **10,000 a second**") shouldBe true
            page.contains("Ceiling over a socket: **500 a second**") shouldBe true
        }
    }

    @Test
    fun `a rate that failed requests is not the over-the-socket ceiling`() {
        val page = report(
            withoutASocket = listOf(row(1_000, behind = 20.microseconds)),
            overASocket = listOf(
                row(500, behind = 40.microseconds, served = 300.microseconds),
                row(1_000, behind = 50.microseconds, served = 400.microseconds, failed = 12),
            ),
        )

        withClue(page) { page.contains("Ceiling over a socket: **500 a second**") shouldBe true }
    }

    @Test
    fun `a row with failures says what they were`() {
        val page = report(
            withoutASocket = emptyList(),
            overASocket = listOf(row(1_000, behind = 50.microseconds, served = 400.microseconds, failed = 12)),
        )

        val line = page.lines().single { it.startsWith("| 1,000 |") }
        withClue(line) { line.contains("ConnectException 12") shouldBe true }
    }

    @Test
    fun `the over-the-socket table carries what the target itself took`() {
        val page = report(
            withoutASocket = emptyList(),
            overASocket = listOf(row(500, behind = 40.microseconds, served = 12.milliseconds)),
        )

        val line = page.lines().single { it.startsWith("| 500 |") }
        withClue(line) {
            page.contains("Served p99") shouldBe true
            line.contains("12.0") shouldBe true
        }
    }

    @Test
    fun `the over-the-socket ceiling is published as the lower bound it is`() {
        val page = report(
            withoutASocket = emptyList(),
            overASocket = listOf(row(500, behind = 40.microseconds, served = 300.microseconds)),
        )

        withClue(page) { page.lowercase().contains("lower bound") shouldBe true }
    }

    private fun row(rate: Int, behind: Duration, served: Duration? = null, failed: Int = 0): Measured =
        Measured(
            rate = rate,
            result = RunResult(
                startedAt = Instant.EPOCH,
                steps = mapOf("step" to stats(ok = SAMPLES, failed = failed)),
                behind = flat(behind, SAMPLES),
            ),
            load = 1.0,
            served = served?.let { flat(it, SAMPLES) },
        )

    private fun stats(ok: Int, failed: Int): StepStats = StepStats(
        name = "step",
        ok = Outcome(flat(1.milliseconds, ok), flat(1.milliseconds, ok)),
        failed = Outcome(
            flat(1.milliseconds, failed),
            flat(1.milliseconds, failed),
            reasons = if (failed == 0) emptyMap() else mapOf(Threw("ConnectException") to failed.toLong()),
        ),
        serviceTime = flat(1.milliseconds, ok + failed),
        responseTime = flat(1.milliseconds, ok + failed),
    )

    private fun flat(each: Duration, samples: Int): Timing =
        Histogram().apply { repeat(samples) { record(each) } }.timing()

    private companion object {
        const val SAMPLES = 1_000
    }
}
