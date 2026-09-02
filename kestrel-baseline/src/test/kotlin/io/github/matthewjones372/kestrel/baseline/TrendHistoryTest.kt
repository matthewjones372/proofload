package io.github.matthewjones372.kestrel.baseline

import io.github.matthewjones372.kestrel.Histogram
import io.github.matthewjones372.kestrel.Machine
import io.github.matthewjones372.kestrel.Outcome
import io.github.matthewjones372.kestrel.Plan
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.StepStats
import io.github.matthewjones372.kestrel.Timing
import io.github.matthewjones372.kestrel.constantRate
import io.github.matthewjones372.kestrel.p99
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.step
import io.github.matthewjones372.kestrel.timing
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A trend is a directory of directories, the way a baseline is a file: one
 * subdirectory per point, because the file carries no label to key on.
 */
class TrendHistoryTest {

    private val here = Machine(cores = 8, jdk = "21.0.2+13", os = "Linux", arch = "aarch64")

    private val paying = Plan(
        scenario = "paying",
        steps = listOf("pay"),
        profile = constantRate(100.perSecond, over = 2.seconds),
    )

    private fun runOf(
        latency: Duration,
        startedAt: Instant,
        machine: Machine = here,
        plan: Plan = paying,
    ): RunResult {
        val whole = Histogram().apply { repeat(SAMPLES) { record(latency) } }.timing()
        return RunResult(
            startedAt = startedAt,
            steps = mapOf("pay" to StepStats("pay", Outcome(whole, whole), Outcome.none, whole, whole)),
            behind = Timing.none,
            plan = plan,
            machine = machine,
        )
    }

    /** A point of five runs, written into its own subdirectory as a shell loop would leave them. */
    private fun point(
        history: Path,
        label: String,
        millis: Int,
        measuredAt: Instant,
        machine: Machine = here,
        plan: Plan = paying,
    ) {
        SPREAD.forEachIndexed { number, off ->
            runOf((millis + off).milliseconds, measuredAt.plusSeconds(number.toLong()), machine, plan)
                .writeBaseline(history.resolve(label).resolve("run-$number.kestrel"))
        }
    }

    private fun noon(day: Int) = Instant.parse("2026-08-26T09:00:00Z").plusSeconds(day * 86_400L)

    @Test
    fun `twenty subdirectories read back as twenty labelled points`(@TempDir history: Path) {
        (0 until 20).forEach { point(history, "commit-$it", 100 + it, noon(it)) }

        val trend = readTrend(history, p99(pay))

        trend.points.size shouldBe 20
        trend.points.map { it.label } shouldBe (0 until 20).map { "commit-$it" }
    }

    @Test
    fun `the points are in the order they were measured, not the order they are named`(@TempDir history: Path) {
        point(history, "zulu", 100, noon(1))
        point(history, "alpha", 100, noon(2))
        point(history, "mike", 100, noon(3))

        withClue("a rebuilt commit lands where it was measured, not where its name sorts") {
            readTrend(history, p99(pay)).points.map { it.label } shouldBe listOf("zulu", "alpha", "mike")
        }
    }

    @Test
    fun `a point whose runs were measured on two machines is refused, and named`(@TempDir history: Path) {
        point(history, "good", 100, noon(1))
        point(history, "good-too", 100, noon(2))
        SPREAD.forEachIndexed { number, off ->
            val machine = if (number == 3) here.copy(cores = 4) else here
            runOf((100 + off).milliseconds, noon(3).plusSeconds(number.toLong()), machine)
                .writeBaseline(history.resolve("mixed").resolve("run-$number.kestrel"))
        }

        val why = shouldThrow<IllegalArgumentException> { readTrend(history, p99(pay)) }.message.orEmpty()

        withClue(why) {
            why shouldContain "mixed"
            why shouldContain "measured on"
        }
    }

    @Test
    fun `an empty subdirectory is named rather than skipped`(@TempDir history: Path) {
        (0 until 3).forEach { point(history, "commit-$it", 100, noon(it)) }
        Files.createDirectories(history.resolve("commit-3"))

        val why = shouldThrow<IllegalArgumentException> { readTrend(history, p99(pay)) }.message.orEmpty()

        withClue(why) { why shouldContain "commit-3" }
    }

    @Test
    fun `a history with fewer than three points says so rather than reading as a trend`(@TempDir history: Path) {
        (0 until 2).forEach { point(history, "commit-$it", 100, noon(it)) }

        val why = shouldThrow<IllegalArgumentException> { readTrend(history, p99(pay)) }.message.orEmpty()

        withClue(why) { why shouldContain "two points" }
    }

    @Test
    fun `a directory with no points in it says so rather than reading as an empty trend`(@TempDir history: Path) {
        val why = shouldThrow<IllegalArgumentException> { readTrend(history, p99(pay)) }.message.orEmpty()

        withClue(why) { why shouldContain history.toString() }
    }

    private companion object {
        const val SAMPLES = 500
        val pay = step("pay")
        val SPREAD = listOf(-2, -1, 0, 1, 2)
    }
}
