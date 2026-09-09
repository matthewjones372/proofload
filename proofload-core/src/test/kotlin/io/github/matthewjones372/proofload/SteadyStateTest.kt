package io.github.matthewjones372.proofload

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val began = Instant.parse("2026-08-27T09:00:00Z")

private const val EACH_SECOND = 25
private const val MILLIS_A_SECOND = 1_000
private const val MILLIS_APART = MILLIS_A_SECOND / EACH_SECOND

/**
 * The detector as a function of a recorded timeline, with no clock in it: each
 * run below is a shape somebody could point at on the report, and the verdict
 * is decided by the offsets the requests were recorded at.
 */
class SteadyStateTest {

    /** [service] answers for each second of the run; null is a second nothing ran in. */
    private fun timelineOf(seconds: Int, service: (Int) -> Duration?): List<Second> {
        val recorder = RunRecorder(began)
        repeat(seconds) { second ->
            val took = service(second) ?: return@repeat
            repeat(EACH_SECOND) { index ->
                recorder.record(
                    step = "pay",
                    failure = null,
                    serviceTime = took,
                    schedulingDelay = Duration.ZERO,
                    at = (second * MILLIS_A_SECOND + index * MILLIS_APART).milliseconds,
                )
            }
        }
        return recorder.freeze().timeline
    }

    @Test
    fun `a run that improved and then flattened settles at the second it flattened`() {
        val timeline = timelineOf(60) { second -> if (second < 20) 200.milliseconds else 20.milliseconds }

        timeline.steadyState() shouldBe SteadyState.From(20.seconds)
    }

    @Test
    fun `a run that was still getting faster at the end never settled`() {
        val timeline = timelineOf(60) { second -> (400 - 6 * second).milliseconds }

        val state = timeline.steadyState()

        withClue("a target that improved all the way to the last second: $state") {
            state.shouldBeInstanceOf<SteadyState.NeverSettled>().why shouldContain "faster"
        }
    }

    @Test
    fun `a run that got slower and stayed slower says that, rather than that it never flattened`() {
        val timeline = timelineOf(60) { second -> if (second < 20) 20.milliseconds else 200.milliseconds }

        val state = timeline.steadyState()

        state.shouldBeInstanceOf<SteadyState.NeverSettled>().why shouldContain "got slower and stayed slower"
    }

    @Test
    fun `a four-second run has nothing to detect, and says so rather than guessing`() {
        val timeline = timelineOf(4) { 20.milliseconds }

        val state = timeline.steadyState()

        state.shouldBeInstanceOf<SteadyState.NeverSettled>().why shouldContain
            "${SteadyState.LEAST_INTERVALS} intervals"
    }

    @Test
    fun `a run that recorded nothing settled nowhere`() {
        emptyList<Second>().steadyState().shouldBeInstanceOf<SteadyState.NeverSettled>()
    }

    @Test
    fun `a second nothing ran in is not a fast second`() {
        val timeline = timelineOf(10) { second -> if (second == 8) null else 200.milliseconds }

        withClue("the fixture: second 8 recorded ${timeline[8].count}") { timeline[8].count shouldBe 0L }
        withClue("a flat run with one quiet second in it settled at the start, not never") {
            timeline.steadyState() shouldBe SteadyState.From(Duration.ZERO)
        }
    }

    @Test
    fun `the tolerance clears the error bar of the numbers it is comparing`() {
        withClue("a window's latency is read from a histogram good to ${Histogram.COARSE_PRECISION}") {
            (SteadyState.TOLERANCE > Histogram.COARSE_PRECISION) shouldBe true
        }
    }

    @Test
    fun `a run answers for its own timeline`() {
        val recorder = RunRecorder(began)
        repeat(60 * EACH_SECOND) { index ->
            recorder.record(
                step = "pay",
                failure = null,
                serviceTime = 20.milliseconds,
                schedulingDelay = Duration.ZERO,
                at = (index * MILLIS_APART).milliseconds,
            )
        }
        val result = recorder.freeze()

        result.steadyState shouldBe result.timeline.steadyState()
    }
}
