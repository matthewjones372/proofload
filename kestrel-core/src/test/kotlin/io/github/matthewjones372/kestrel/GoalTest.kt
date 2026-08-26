package io.github.matthewjones372.kestrel

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val pay = step("pay")
private val missing = step("confirm")

class GoalTest {

    private fun timingOf(values: List<Duration>) =
        Histogram().apply { values.forEach { record(it) } }.timing()

    private fun resultOf(goals: List<Goal>, failures: Long = 3L) = RunResult(
        startedAt = Instant.parse("2026-08-26T09:00:00Z"),
        steps = mapOf(
            "pay" to StepStats(
                name = "pay",
                count = 100L,
                ok = 100L - failures,
                failures = mapOf("status 503" to failures),
                serviceTime = timingOf(listOf(50.milliseconds)),
                responseTime = timingOf(listOf(150.milliseconds)),
            ),
        ),
        behind = timingOf(listOf(1.milliseconds)),
        plan = Plan(scenario = "checkout", steps = listOf("pay"), profile = null, goals = goals),
    )

    @Test
    fun `a run with no goals has nothing to miss`() {
        val result = resultOf(emptyList())

        result.verdicts.shouldBeEmpty()
        result.metEveryGoal shouldBe true
    }

    @Test
    fun `a percentile goal reads response time, which is what a user would have seen`() {
        resultOf(listOf(p99(pay) under 200.milliseconds)).verdicts.single().met shouldBe true
        resultOf(listOf(p99(pay) under 100.milliseconds)).verdicts.single().met shouldBe false
    }

    @Test
    fun `a goal can ask about the target alone`() {
        resultOf(listOf(p99(pay, of = Clock.ServiceTime) under 100.milliseconds)).verdicts.single().met shouldBe true
    }

    @Test
    fun `a missed goal says by how much, as a share of the target`() {
        val verdict = resultOf(listOf(p99(pay) under 100.milliseconds)).verdicts.single()

        verdict.met shouldBe false
        // 151ms against 100ms: about half again.
        val over = requireNotNull(verdict.overBy)
        (over > 40.0) shouldBe true
        (over < 70.0) shouldBe true
    }

    @Test
    fun `a failure rate goal counts the whole run unless it names a step`() {
        resultOf(listOf(failureRate under 5.percent)).verdicts.single().met shouldBe true
        resultOf(listOf(failureRate under 1.percent)).verdicts.single().met shouldBe false
    }

    @Test
    fun `a goal against a step that never ran is missed, not quietly skipped`() {
        val verdict = resultOf(listOf(p99(missing) under 1.seconds)).verdicts.single()

        verdict.met shouldBe false
        verdict.measured shouldBe Measurement.Absent("the step never ran")
    }

    @Test
    fun `keeping the schedule is a goal like any other`() {
        resultOf(listOf(keptSchedule)).verdicts.single().met shouldBe true
    }

    @Test
    fun `goals travel from the simulation to the result`() {
        val simulation = scenario("checkout") { exec(pay) { } }
            .at(1.perSecond, over = 1.seconds)
            .expecting(p99(pay) under 200.milliseconds, keptSchedule)

        simulation.plan().goals.size shouldBe 2
    }
}
