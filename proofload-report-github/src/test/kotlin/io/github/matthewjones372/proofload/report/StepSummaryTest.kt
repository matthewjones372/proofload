package io.github.matthewjones372.proofload.report

import io.github.matthewjones372.proofload.Histogram
import io.github.matthewjones372.proofload.Outcome
import io.github.matthewjones372.proofload.RunResult
import io.github.matthewjones372.proofload.StepStats
import io.github.matthewjones372.proofload.against
import io.github.matthewjones372.proofload.timing
import io.kotest.matchers.longs.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

class StepSummaryTest {

    private val latency = Histogram().apply { record(10.milliseconds) }.timing()

    private val result = RunResult(
        startedAt = Instant.parse("2026-08-26T09:00:00Z"),
        steps = mapOf(
            "pay" to StepStats(
                "pay",
                ok = Outcome(latency, latency),
                failed = Outcome.none,
                serviceTime = latency,
                responseTime = latency,
            ),
        ),
        behind = Histogram().apply { record(10.milliseconds) }.timing(),
    )

    private fun pointingAt(file: Path): (String) -> String? =
        { name -> if (name == "GITHUB_STEP_SUMMARY") file.toString() else null }

    @Test
    fun `the job summary is appended to, so what an earlier step wrote survives`(@TempDir dir: Path) {
        val file = dir.resolve("summary.md")
        Files.writeString(file, "an earlier step wrote this\n")

        val answer = result.appendToStepSummary(environment = pointingAt(file))

        answer shouldBe StepSummary.Appended(file)
        Files.readString(file) shouldStartWith "an earlier step wrote this\n"
        Files.readString(file) shouldContain "| Step"
    }

    @Test
    fun `two runs in one job leave two tables, not one overwriting the other`(@TempDir dir: Path) {
        val file = dir.resolve("summary.md")

        result.appendToStepSummary(environment = pointingAt(file))
        result.appendToStepSummary(environment = pointingAt(file))

        Files.readString(file).windowed(6).count { it == "| Step" } shouldBe 2
    }

    @Test
    fun `the comparison reaches the job summary, which is where a pull request reads it`(@TempDir dir: Path) {
        val file = dir.resolve("summary.md")

        result.appendToStepSummary(comparison = result.against(null), environment = pointingAt(file))

        Files.readString(file) shouldContain "Not compared to the last run."
    }

    @Test
    fun `the file GitHub names need not exist yet`(@TempDir dir: Path) {
        val file = dir.resolve("summary.md")

        result.appendToStepSummary(environment = pointingAt(file))

        Files.readString(file) shouldContain "| Step"
    }

    @Test
    fun `with the variable unset nothing is written and the return value says so`(@TempDir dir: Path) {
        val answer = result.appendToStepSummary { null }

        answer shouldBe StepSummary.NotOnActions
        Files.list(dir).use { it.count() shouldBeExactly 0L }
    }

    @Test
    fun `a variable set to blank names no file`(@TempDir dir: Path) {
        result.appendToStepSummary { "   " } shouldBe StepSummary.NotOnActions

        Files.list(dir).use { it.count() shouldBeExactly 0L }
    }

    @Test
    fun `the variable read is the one GitHub Actions sets`() {
        val asked = mutableListOf<String>()

        result.appendToStepSummary { name -> asked.add(name); null }

        asked shouldBe listOf("GITHUB_STEP_SUMMARY")
    }

    @Test
    fun `the lookup defaults to the real environment, so a laptop run is a no-op`() {
        assumeTrue(System.getenv("GITHUB_STEP_SUMMARY").isNullOrBlank(), "this asserts the off-Actions path")

        result.appendToStepSummary() shouldBe StepSummary.NotOnActions
    }
}
