package io.github.matthewjones372.kestrel.export

import io.github.matthewjones372.kestrel.Clock
import io.github.matthewjones372.kestrel.Histogram
import io.github.matthewjones372.kestrel.InjectionProfile
import io.github.matthewjones372.kestrel.Outcome
import io.github.matthewjones372.kestrel.Plan
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.Said
import io.github.matthewjones372.kestrel.StepName
import io.github.matthewjones372.kestrel.StepStats
import io.github.matthewjones372.kestrel.Timing
import io.github.matthewjones372.kestrel.p99
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.timing
import io.kotest.assertions.withClue
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * The headline is what a caller reads first, so what outranks what is the claim
 * worth pinning: a run whose generator lost its schedule did not measure the
 * target, whatever its goals say.
 */
class RunJsonVerdictsTest {

    @Test
    fun `a run that met every goal says so`() {
        val met = run(goal = p99(StepName("pay"), of = Clock.ServiceTime) under 500.milliseconds)

        met.json() shouldContain """"verdict": "met""""
    }

    @Test
    fun `a goal that missed carries what it measured and by how much`() {
        val missed = run(goal = p99(StepName("pay"), of = Clock.ServiceTime) under 1.milliseconds).json()

        withClue("a caller acting on this needs the margin, not just the cross") {
            missed shouldContain """"verdict": "missed""""
            missed shouldContain """"met": false"""
            missed shouldContain """"overBy""""
        }
    }

    @Test
    fun `a run that fell behind outranks the goals it also missed`() {
        val behind = run(
            goal = p99(StepName("pay"), of = Clock.ServiceTime) under 1.milliseconds,
            behind = Histogram().apply { repeat(50) { record(80.milliseconds) } }.timing(),
        ).json()

        withClue("the tail is not the target's, so a missed goal is not the answer to report") {
            behind shouldContain """"verdict": "behind""""
        }
    }

    @Test
    fun `a run nobody set a goal is not a run that met one`() {
        val unasked = RunResult(
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
            behind = Timing.none,
        )

        withClue("a tick nobody earned is what the verdicts exist to stop printing") {
            unasked.json() shouldContain """"verdict": "nothingAsked""""
        }
    }

    @Test
    fun `the schedule block says whether it was kept`() {
        val document = run(goal = p99(StepName("pay"), of = Clock.ServiceTime) under 500.milliseconds).json()

        document shouldContain """"schedule": {"""
        document shouldContain """"kept": true"""
    }

    @Test
    fun `every goal is named as it was asked`() {
        run(goal = p99(StepName("pay"), of = Clock.ServiceTime) under 500.milliseconds)
            .json() shouldContain """"asked": "pay p99 under 500ms""""
    }

    private fun timingOf(vararg micros: Long): Timing =
        Histogram().apply { micros.forEach { record(it.microseconds) } }.timing()

    private fun run(goal: io.github.matthewjones372.kestrel.Goal, behind: Timing = Timing.none): RunResult =
        RunResult(
            startedAt = Instant.parse("2026-09-02T09:00:00Z"),
            steps = mapOf(
                "pay" to StepStats(
                    name = "pay",
                    ok = Outcome(timingOf(900, 1_100), timingOf(950, 1_150)),
                    failed = Outcome(timingOf(300_000), timingOf(300_050), mapOf(Said("status 503") to 1L)),
                    serviceTime = timingOf(900, 1_100, 300_000),
                    responseTime = timingOf(950, 1_150, 300_050),
                ),
            ),
            behind = behind,
            plan = Plan(
                scenario = "checkout",
                steps = listOf("pay"),
                profile = InjectionProfile.ConstantRate(50.perSecond.perSecond, 1.minutes),
                goals = listOf(goal),
            ),
        )
}
