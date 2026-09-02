package io.github.matthewjones372.kestrel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What a series says that no pair of its points can: a creep of two percent a
 * point sits inside every adjacent interval and moves a third over forty.
 */
class TrendTest {

    private val here = Machine(cores = 8, jdk = "21.0.2+13", os = "Linux", arch = "aarch64")

    private val paying = Plan(
        scenario = "paying",
        steps = listOf("pay"),
        profile = constantRate(100.perSecond, over = 2.seconds),
    )

    private fun runOf(latency: Duration, machine: Machine): RunResult {
        val timing = Histogram().apply { repeat(SAMPLES) { record(latency) } }.timing()
        return RunResult(
            startedAt = Instant.parse("2026-08-26T09:00:00Z"),
            steps = mapOf("pay" to StepStats("pay", Outcome(timing, timing), Outcome.none, timing, timing)),
            behind = Timing.none,
            plan = paying,
            machine = machine,
        )
    }

    /** A point of five runs that landed a little apart, centred on [millis]. */
    private fun pointAt(label: String, millis: Int, machine: Machine = here) =
        Trend.Point(label, Runs(SPREAD.map { runOf((millis + it).milliseconds, machine) }))

    private fun trendOf(vararg points: Trend.Point) = Trend(p99(pay), points.toList())

    @Test
    fun `a series that never moved names no step, and its ends contain no change`() {
        val flat = trendOf(pointAt("a", 100), pointAt("b", 100), pointAt("c", 100), pointAt("d", 100))

        flat.steps.shouldBeEmpty()
        withClue("${flat.ends.interval}") { (1.0 in flat.ends.interval!!) shouldBe true }
    }

    @Test
    fun `one point that moved names that pair, and only that pair`() {
        val stepped = trendOf(pointAt("a", 100), pointAt("b", 100), pointAt("c", 200), pointAt("d", 200))

        stepped.steps.map { "${it.from.label}-${it.to.label}" } shouldBe listOf("b-c")
        stepped.comparisons shouldBe 3
    }

    @Test
    fun `a creep no adjacent pair can resolve still shows between the ends`() {
        val creeping = (0..19).map { pointAt("$it", 100 + it * 2) }

        val trend = Trend(p99(pay), creeping)

        withClue("the pairwise blindness this exists for: ${trend.steps.map { it.to.label }}") {
            trend.steps.shouldBeEmpty()
            trend.ends.ratio shouldBe ((138.0 / 100.0) plusOrMinus 0.06)
            (1.0 in trend.ends.interval!!) shouldBe false
        }
    }

    @Test
    fun `two points with bands is a pairwise comparison wearing a chart`() {
        val why = shouldThrow<IllegalArgumentException> { trendOf(pointAt("a", 100), pointAt("b", 200)) }.message

        withClue(why.orEmpty()) { why.orEmpty() shouldContain "two points" }
    }

    @Test
    fun `a machine change is a break, not a step`() {
        val elsewhere = here.copy(cores = 4)
        val moved = trendOf(
            pointAt("a", 100),
            pointAt("b", 100),
            pointAt("c", 200, elsewhere),
            pointAt("d", 200, elsewhere),
        )

        withClue("the pair that straddles it moved, and nothing here can say the runner did not") {
            moved.steps.map { "${it.from.label}-${it.to.label}" }.shouldBeEmpty()
            moved.comparisons shouldBe 2
        }
    }

    @Test
    fun `how many comparisons were made, and how many of them the machine is expected to fake`() {
        val flat = (0..39).map { pointAt("$it", 100) }

        val trend = Trend(p99(pay), flat)

        trend.comparisons shouldBe 39
        withClue("39 pairs at 95% expects about two spurious steps in a series that never moved") {
            trend.stepsExpectedFromNoise shouldBe (39 * 0.05 plusOrMinus 0.001)
        }
    }

    private companion object {
        const val SAMPLES = 500
        val pay = step("pay")
        val SPREAD = listOf(-2, -1, 0, 1, 2)
    }
}
