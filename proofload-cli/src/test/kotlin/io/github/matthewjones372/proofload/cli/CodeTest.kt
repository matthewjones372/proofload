package io.github.matthewjones372.proofload.cli

import io.github.matthewjones372.proofload.Clock
import io.github.matthewjones372.proofload.Goal
import io.github.matthewjones372.proofload.Histogram
import io.github.matthewjones372.proofload.InjectionProfile
import io.github.matthewjones372.proofload.Outcome
import io.github.matthewjones372.proofload.Plan
import io.github.matthewjones372.proofload.RunResult
import io.github.matthewjones372.proofload.StepName
import io.github.matthewjones372.proofload.StepStats
import io.github.matthewjones372.proofload.Timing
import io.github.matthewjones372.proofload.p99
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.timing
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * What a shell is told, decided on a result rather than on a run: the mapping
 * is the logic, and a test that had to send requests to reach it would be
 * measuring the machine it shares.
 */
class CodeTest {

    @Test
    fun `the numbers are what a shell script was written against`() {
        withClue("these are published in the usage text; reordering the enum must not move them") {
            Code.Met.number shouldBe 0
            Code.Missed.number shouldBe 1
            Code.Behind.number shouldBe 2
            Code.Refused.number shouldBe 3
            Code.Unusable.number shouldBe 4
        }
    }

    @Test
    fun `a run that met its goals exits zero`() {
        result(goal = p99(StepName("pay"), of = Clock.ServiceTime) under 500.milliseconds).code() shouldBe Code.Met
    }

    @Test
    fun `a missed goal exits one`() {
        result(goal = p99(StepName("pay"), of = Clock.ServiceTime) under 1.microseconds).code() shouldBe Code.Missed
    }

    @Test
    fun `a generator that lost its schedule outranks the goal it also missed`() {
        val behind = result(
            goal = p99(StepName("pay"), of = Clock.ServiceTime) under 1.microseconds,
            behind = Histogram().apply { repeat(50) { record(80.milliseconds) } }.timing(),
        )

        withClue("a shell branching on this must not be told the target was slow") {
            behind.code() shouldBe Code.Behind
        }
    }

    @Test
    fun `a run nobody set a goal exits zero`() {
        result(goal = null).code() shouldBe Code.Met
    }

    private fun timingOf(vararg micros: Long): Timing =
        Histogram().apply { micros.forEach { record(it.microseconds) } }.timing()

    private fun result(goal: Goal?, behind: Timing = Timing.none): RunResult = RunResult(
        startedAt = Instant.parse("2026-09-02T09:00:00Z"),
        steps = mapOf(
            "pay" to StepStats(
                name = "pay",
                ok = Outcome(timingOf(900), timingOf(950)),
                failed = Outcome(Timing.none, Timing.none),
                serviceTime = timingOf(900),
                responseTime = timingOf(950),
            ),
        ),
        behind = behind,
        plan = Plan(
            scenario = "checkout",
            steps = listOf("pay"),
            profile = InjectionProfile.ConstantRate(50.perSecond.perSecond, 1.minutes),
            goals = listOfNotNull(goal),
        ),
    )
}
