package io.github.matthewjones372.kestrel

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val began = Instant.parse("2026-08-27T09:00:00Z")

private const val SECONDS_RUN = 60
private const val WARM_UP = 20
private const val EACH_SECOND = 25
private const val MILLIS_A_SECOND = 1_000
private const val MILLIS_APART = MILLIS_A_SECOND / EACH_SECOND
private const val RUNS = 10

private val pay = step("pay")

/**
 * A run that was slow for twenty seconds and then was not, judged over the
 * segment it settled into rather than over the whole of itself.
 */
class SteadySegmentTest {

    private fun warmedUp(goals: List<Goal> = emptyList(), failingIn: (Int) -> Boolean = { false }): RunResult {
        val recorder = RunRecorder(began)
        repeat(SECONDS_RUN) { second ->
            val cold = second < WARM_UP
            repeat(EACH_SECOND) { index ->
                recorder.record(
                    step = pay.name,
                    failure = if (failingIn(second)) "status 503" else null,
                    serviceTime = if (cold) 200.milliseconds else 20.milliseconds,
                    schedulingDelay = 5.milliseconds,
                    at = (second * MILLIS_A_SECOND + index * MILLIS_APART).milliseconds,
                )
            }
        }
        return recorder.freeze().copy(plan = Plan("checkout", listOf(pay.name), profile = null, goals = goals))
    }

    @Test
    fun `a slow first twenty seconds is not in the steady segment's p99`() {
        val result = warmedUp()

        withClue("the whole run, which keeps the cold start: ${result[pay].serviceTime.p99}") {
            (result[pay].serviceTime.p99 >= 200.milliseconds) shouldBe true
        }
        withClue("the steady segment: ${result.steady[pay].serviceTime.p99}") {
            (result.steady[pay].serviceTime.p99 < 25.milliseconds) shouldBe true
        }
    }

    @Test
    fun `the steady segment counts the requests that left inside it, and no others`() {
        val result = warmedUp()

        result.steady[pay].count shouldBe (SECONDS_RUN - WARM_UP).toLong() * EACH_SECOND
        result.steady.count shouldBe result.count - WARM_UP.toLong() * EACH_SECOND
    }

    @Test
    fun `the steady segment is a run of its own, starting where it settled`() {
        val result = warmedUp()

        result.steady.startedAt shouldBe began.plusSeconds(WARM_UP.toLong())
        result.steady.timeline.size shouldBe SECONDS_RUN - WARM_UP
    }

    @Test
    fun `nothing is discarded by default, so the whole run is still there to read`() {
        val result = warmedUp()

        result.timeline.size shouldBe SECONDS_RUN
        result.count shouldBe SECONDS_RUN.toLong() * EACH_SECOND
    }

    @Test
    fun `a run that never settled is not restricted`() {
        val result = warmedUp().copy(timeline = emptyList())

        result.steady shouldBe result
    }

    @Test
    fun `a goal on the target's service time is judged over the steady segment`() {
        val result = warmedUp(goals = listOf(p99(pay, of = Clock.ServiceTime) under 100.milliseconds))

        withClue("the whole run's p99 was ${result[pay].serviceTime.p99}, the segment's is under the limit") {
            result.verdicts.single().met shouldBe true
        }
    }

    @Test
    fun `a goal on failures is judged over the steady segment`() {
        val result = warmedUp(goals = listOf(failureRate under 1.percent), failingIn = { it < WARM_UP })

        withClue("${result.failed} of ${result.count} failed, all of them before it settled") {
            result.verdicts.single().met shouldBe true
        }
    }

    @Test
    fun `a goal on response time is judged over the whole run, which is where response time was measured`() {
        val result = warmedUp(goals = listOf(p99(pay, of = Clock.ResponseTime) under 100.milliseconds))

        val verdict = result.verdicts.single()

        withClue("the segment keeps no response times, so a goal on them cannot be quietly narrowed") {
            verdict.met shouldBe false
            verdict.measured shouldBe Measurement.Took(result[pay].responseTime.p99)
        }
    }

    @Test
    fun `the segment counts what failed in it and cannot say what the target called it`() {
        val result = warmedUp(failingIn = { true })

        result.steady[pay].failed.count shouldBe (SECONDS_RUN - WARM_UP).toLong() * EACH_SECOND
        withClue("a second counts what failed and not what the target said about it") {
            result.steady[pay].failed.reasons shouldBe emptyMap()
        }
        result.steady[pay].responseTime shouldBe Timing.none
    }

    @Test
    fun `ten runs of one shape settle where one of them settles`() {
        val one = warmedUp()

        val merged = Runs(List(RUNS) { warmedUp() }).merged

        withClue("second n of ten runs is second n of the merge, so the cold start is still at the front") {
            merged.steadyState shouldBe one.steadyState
        }
        merged.steady[pay].count shouldBe one.steady[pay].count * RUNS
        withClue("and the merged segment's p99 is over the warm seconds of all ten") {
            (merged.steady[pay].serviceTime.p99 < 25.milliseconds) shouldBe true
        }
    }

    @Test
    fun `a step that ran only while the run was cold recorded nothing in the segment`() {
        val recorder = RunRecorder(began)
        repeat(SECONDS_RUN) { second ->
            repeat(EACH_SECOND) { index ->
                recorder.record(
                    step = pay.name,
                    failure = null,
                    serviceTime = if (second < WARM_UP) 200.milliseconds else 20.milliseconds,
                    schedulingDelay = Duration.ZERO,
                    at = (second * MILLIS_A_SECOND + index * MILLIS_APART).milliseconds,
                )
            }
        }
        recorder.record(
            step = "sign in",
            failure = null,
            serviceTime = 900.milliseconds,
            schedulingDelay = Duration.ZERO,
            at = 1.seconds,
        )
        val result = recorder.freeze()

        result.steady["sign in"].count shouldBe 0L
    }
}
