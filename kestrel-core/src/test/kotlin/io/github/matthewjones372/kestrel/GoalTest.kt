package io.github.matthewjones372.kestrel

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
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

    private fun outcomeOf(samples: Int, reasons: Map<Reason, Long> = emptyMap()) = Outcome(
        serviceTime = timingOf(List(samples) { 50.milliseconds }),
        responseTime = timingOf(List(samples) { 150.milliseconds }),
        reasons = reasons,
    )

    private fun resultOf(goals: List<Goal>, failures: Long = 3L) = RunResult(
        startedAt = Instant.parse("2026-08-26T09:00:00Z"),
        steps = mapOf(
            "pay" to StepStats(
                name = "pay",
                ok = outcomeOf((100L - failures).toInt()),
                failed = outcomeOf(failures.toInt(), mapOf(Said("status 503") to failures)),
                serviceTime = timingOf(List(100) { 50.milliseconds }),
                responseTime = timingOf(List(100) { 150.milliseconds }),
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
    fun `a goodput goal counts the requests that both succeeded and came back in time`() {
        val goals = listOf(goodput(pay, under = 200.milliseconds) atLeast 95.percent)

        resultOf(goals, failures = 3L).verdicts.single().met shouldBe true
        resultOf(goals, failures = 8L).verdicts.single().met shouldBe false
    }

    @Test
    fun `a missed goodput goal shows the share it measured, not just that it missed`() {
        val verdict = resultOf(listOf(goodput(pay, under = 200.milliseconds) atLeast 99.percent)).verdicts.single()

        verdict.met shouldBe false
        withClue("3 of 100 failed, and none of the rest was slow") {
            verdict.measured.shouldBeInstanceOf<Measurement.Share>().percent shouldBe (97.0 plusOrMinus 1e-9)
        }
    }

    @Test
    fun `a goodput goal reads response time, so a target it misses by waiting still misses`() {
        val goals = listOf(goodput(pay, under = 100.milliseconds) atLeast 90.percent)

        resultOf(goals).verdicts.single().met shouldBe false
        resultOf(listOf(goodput(pay, under = 100.milliseconds, of = Clock.ServiceTime) atLeast 90.percent))
            .verdicts.single().met shouldBe true
    }

    @Test
    fun `a goal against a step that never ran is missed, not quietly skipped`() {
        val verdict = resultOf(listOf(p99(missing) under 1.seconds)).verdicts.single()

        verdict.met shouldBe false
        verdict.measured shouldBe Measurement.Absent("the step never ran")
    }

    /** Enough samples that a p99.9 has one to rest on, which is what the tail goal needs. */
    private fun resultOf(goals: List<Goal>, samples: Int) = RunResult(
        startedAt = Instant.parse("2026-08-26T09:00:00Z"),
        steps = mapOf(
            "pay" to StepStats(
                name = "pay",
                ok = outcomeOf(samples),
                failed = Outcome.none,
                serviceTime = timingOf(List(samples) { 50.milliseconds }),
                responseTime = timingOf(List(samples) { 150.milliseconds }),
            ),
        ),
        behind = timingOf(listOf(1.milliseconds)),
        plan = Plan(scenario = "checkout", steps = listOf("pay"), profile = null, goals = goals),
    )

    @Test
    fun `a tail goal is judged like the percentiles beside it`() {
        val goals = listOf(p999(pay) under 200.milliseconds)

        resultOf(goals, samples = 1_000).verdicts.single().met shouldBe true
        resultOf(listOf(p999(pay) under 100.milliseconds), samples = 1_000).verdicts.single().met shouldBe false
    }

    @Test
    fun `a step too thin to have measured its tail misses the goal, and says why`() {
        val verdict = resultOf(listOf(p999(pay) under 1.seconds), samples = 999).verdicts.single()

        verdict.met shouldBe false
        withClue("measured ${verdict.measured}") {
            verdict.measured.shouldBeInstanceOf<Measurement.Absent>().because shouldContain "999"
        }
    }

    @Test
    fun `a tail goal prints the percentile it asked for, not the name of its builder`() {
        (p999(pay) under 1.seconds).described shouldContain "p99.9"
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
