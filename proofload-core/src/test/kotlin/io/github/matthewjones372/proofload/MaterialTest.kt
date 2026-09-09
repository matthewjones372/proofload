package io.github.matthewjones372.proofload

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * The threshold decides the verdict everything else rests on, so what it lets
 * through and what it catches are both worth pinning.
 */
class MaterialTest {

    @Test
    fun `a flat five milliseconds against a long tail is not falling behind`() {
        // The run this was found on: 5ms of constant lateness, a 356ms tail,
        // a flat timeline and Little's law agreeing. Called behind by a
        // threshold that was a bucket width.
        val run = run(tail = 356.milliseconds, late = 5.milliseconds)

        withClue("one and a half percent is not a tail worth distrusting") {
            run.fellBehind() shouldBe false
        }
    }

    @Test
    fun `lateness of a fifth of the tail is`() {
        run(tail = 100.milliseconds, late = 20.milliseconds).fellBehind() shouldBe true
    }

    @Test
    fun `the threshold is a judgement somebody can read`() {
        withClue("a figure nobody can find is a figure nobody can argue with") {
            MATERIAL shouldBe 0.05
        }
    }

    @Test
    fun `a run with no steps has nothing to be late against`() {
        RunResult(startedAt = Instant.EPOCH, steps = emptyMap(), behind = Timing.none)
            .fellBehind() shouldBe false
    }

    private fun timingOf(each: Duration, times: Int): Timing =
        Histogram().apply { repeat(times) { record(each) } }.timing()

    private fun run(tail: Duration, late: Duration): RunResult = RunResult(
        startedAt = Instant.parse("2026-09-04T21:15:10Z"),
        steps = mapOf(
            "search" to StepStats(
                name = "search",
                ok = Outcome(timingOf(tail, 100), timingOf(tail, 100)),
                failed = Outcome(Timing.none, Timing.none),
                serviceTime = timingOf(tail, 100),
                responseTime = timingOf(tail, 100),
            ),
        ),
        behind = timingOf(late, 100),
        plan = Plan(
            scenario = "baseline",
            steps = listOf("search"),
            profile = InjectionProfile.ConstantRate(30.perSecond.perSecond, 1.minutes),
        ),
    )
}
