package io.github.matthewjones372.proofload

import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * A verdict says what happened. The remedy says what to do about it, in the
 * words a reader would otherwise have to work out — and never by naming a
 * number nothing measured.
 */
class RemedyTest {

    @Test
    fun `a goal that was met has nothing to suggest`() {
        judged(limit = 500.milliseconds).remedy.shouldBeNull()
    }

    @Test
    fun `a missed goal names the step and what it would take`() {
        val remedy = requireNotNull(judged(limit = 100.microseconds).remedy)

        withClue(remedy) {
            remedy shouldContain "pay"
        }
    }

    @Test
    fun `a refused verdict says what its own refusal already said`() {
        val refused = Verdict(
            goal = p99(StepName("pay")) under 1.milliseconds,
            met = true,
            measured = Measurement.Absent("nothing ran"),
            overBy = null,
            refused = Tell.CannotTell(why = "under the floor", wouldChangeIt = "more runs"),
        )

        withClue("wouldChangeIt is already the remedy; a second sentence beside it is two answers") {
            refused.remedy shouldBe "more runs"
        }
    }

    @Test
    fun `a run that kept its schedule has no schedule remedy`() {
        run(behind = Timing.none).scheduleRemedy.shouldBeNull()
    }

    @Test
    fun `a run that lost ground says so with the two numbers it measured`() {
        val late = Histogram().apply { repeat(50) { record(80.milliseconds) } }.timing()
        val remedy = requireNotNull(run(behind = late).scheduleRemedy)

        withClue(remedy) {
            remedy shouldContain "80"
            remedy shouldContain "20"
        }
    }

    @Test
    fun `a run that fell behind without losing ground quotes the tail, not the interval`() {
        // Late by less than the interval, so `lostGround` is false, but large
        // against a tail this short, so `fellBehind` is true.
        val late = Histogram().apply { repeat(50) { record(2.milliseconds) } }.timing()
        val remedy = requireNotNull(run(behind = late).scheduleRemedy)

        withClue(remedy) {
            withClue("quoting the interval here would argue against the verdict it explains") {
                remedy shouldNotContain "planned between departures"
            }
            remedy shouldContain "tail"
        }
    }

    @Test
    fun `the schedule remedy names no rate nobody measured`() {
        val late = Histogram().apply { repeat(50) { record(80.milliseconds) } }.timing()
        val remedy = requireNotNull(run(behind = late).scheduleRemedy)

        withClue("a suggested rate would be a number in a report that nothing measured") {
            remedy shouldContain "lower"
        }
    }

    private fun timingOf(vararg micros: Long): Timing =
        Histogram().apply { micros.forEach { record(it.microseconds) } }.timing()

    private fun run(behind: Timing): RunResult = RunResult(
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
        ),
    )

    private fun judged(limit: kotlin.time.Duration): Verdict =
        (p99(StepName("pay"), of = Clock.ServiceTime) under limit).judge(run(behind = Timing.none))
}
