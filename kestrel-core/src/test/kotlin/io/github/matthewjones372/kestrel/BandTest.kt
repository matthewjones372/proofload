package io.github.matthewjones372.kestrel

import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * One point of a trend is a set of runs, and what it can claim is not the
 * reading alone but how far the runs behind it landed apart.
 */
class BandTest {

    private val here = Machine(cores = 8, jdk = "21.0.2+13", os = "Linux", arch = "aarch64")

    private val paying = Plan(
        scenario = "paying",
        steps = listOf("pay"),
        profile = constantRate(100.perSecond, over = 2.seconds),
    )

    private fun runOf(latency: Duration): RunResult {
        val timing = Histogram().apply { repeat(SAMPLES) { record(latency) } }.timing()
        return RunResult(
            startedAt = Instant.parse("2026-08-26T09:00:00Z"),
            steps = mapOf("pay" to StepStats("pay", Outcome(timing, timing), Outcome.none, timing, timing)),
            behind = Timing.none,
            plan = paying,
            machine = here,
        )
    }

    private fun runsAt(vararg millis: Int): Runs = Runs(millis.map { runOf(it.milliseconds) })

    @Test
    fun `a band holds the reading the point was read at`() {
        val point = runsAt(98, 99, 100, 101, 102)

        val band = point.band(p99(pay)).shouldNotBeNull()

        val statistic = p99(pay)
        val reading = statistic.read(statistic.samplesIn(point.merged).shouldNotBeNull()).shouldNotBeNull()
        withClue("$band around $reading") { (reading in band.low..band.high) shouldBe true }
    }

    @Test
    fun `runs that landed further apart give a wider band`() {
        val together = runsAt(99, 100, 100, 100, 101).band(p99(pay)).shouldNotBeNull()
        val scattered = runsAt(60, 80, 100, 130, 170).band(p99(pay)).shouldNotBeNull()

        withClue("$scattered against $together") {
            (scattered.high - scattered.low) shouldBeGreaterThan (together.high - together.low)
        }
    }

    @Test
    fun `four runs are not a point, for the reason five are not a comparison`() {
        runsAt(98, 99, 100, 101).band(p99(pay)).shouldBeNull()
    }

    @Test
    fun `a statistic none of these runs measured has no band rather than a zero one`() {
        runsAt(98, 99, 100, 101, 102).band(p99(step("never"))).shouldBeNull()
    }

    private companion object {
        const val SAMPLES = 500
        val pay = step("pay")
    }
}
