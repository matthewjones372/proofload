package io.github.matthewjones372.kestrel.export

import io.github.matthewjones372.kestrel.Histogram
import io.github.matthewjones372.kestrel.InjectionProfile
import io.github.matthewjones372.kestrel.Machine
import io.github.matthewjones372.kestrel.Outcome
import io.github.matthewjones372.kestrel.Plan
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.Said
import io.github.matthewjones372.kestrel.StepName
import io.github.matthewjones372.kestrel.StepStats
import io.github.matthewjones372.kestrel.Threw
import io.github.matthewjones372.kestrel.Timing
import io.github.matthewjones372.kestrel.p99
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.timing
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class RunJsonTest {

    private val summary = Fixture.run.json(Density.Summary)

    @Test
    fun `the summary matches its golden`() {
        summary matches "run-summary.json"
    }

    @Test
    fun `the full document matches its golden`() {
        Fixture.run.json(Density.Full) matches "run-full.json"
    }

    @Test
    fun `every document says which schema it is`() {
        withClue("a reader with no version has to guess, and guesses wrong once") {
            summary shouldContain """"schema": "kestrel/run/1""""
            summary shouldContain """"density": "summary""""
        }
    }

    @Test
    fun `a summary fits in a couple of kilobytes`() {
        withClue("the summary is the density meant to be held in a prompt or a comment") {
            summary.toByteArray(Charsets.UTF_8).size shouldBeLessThan SUMMARY_BUDGET
        }
    }

    @Test
    fun `the summary leaves out what only the full document carries`() {
        withClue("per-step percentiles and the timeline are what Full is for") {
            summary shouldNotContain "timeline"
            summary shouldNotContain "responseTime"
        }
    }

    @Test
    fun `failures are grouped by reason across every step`() {
        summary shouldContain """"reason": "status 503""""
        summary shouldContain """"reason": "ConnectException""""
    }

    @Test
    fun `durations are the nanoseconds the histogram reported`() {
        withClue("the encoding holds the measurement; formatting is the reader's") {
            summary shouldContain """"durationUnit": "nanoseconds""""
        }
    }

    @Test
    fun `writing one leaves the same bytes on disk`(@TempDir dir: Path) {
        val written = Fixture.run.writeJson(dir.resolve("run.json"), Density.Summary)

        Files.readString(written) shouldBe summary
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
            plan = Plan(
                scenario = "checkout",
                steps = listOf("pay"),
                profile = InjectionProfile.ConstantRate(50.perSecond.perSecond, 1.minutes),
                // A goal the run misses, so the golden carries the shape a
                // reader is actually going to parse rather than an empty list.
                goals = listOf(p99(StepName("pay")) under 200.milliseconds),
            ),
            hiccups = Histogram().apply { repeat(2) { record(4.milliseconds) } }.timing(),
            machine = here,
        )
    }

    private companion object {
        const val SUMMARY_BUDGET = 2048
    }
}
