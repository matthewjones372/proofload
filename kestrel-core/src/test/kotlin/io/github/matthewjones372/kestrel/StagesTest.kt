package io.github.matthewjones372.kestrel

import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val began = Instant.parse("2026-08-26T09:00:00Z")

/**
 * A run that ramped and then held reports one p99 over a population that was
 * never offered at one rate: part of it is the easy start and part the hard
 * end, weighted by how long each lasted. Lengthen the ramp and the number
 * improves without the target changing.
 */
class StagesTest {

    /** A second's worth of requests at [service] ms, landing in the second [at] begins. */
    private fun RunRecorder.second(at: Long, service: Long) =
        record(
            step = "pay",
            failure = null,
            serviceTime = service.milliseconds,
            schedulingDelay = Duration.ZERO,
            at = at.seconds,
        )

    private fun ran(profile: InjectionProfile, seconds: Int, service: (Int) -> Long): RunResult {
        val recorder = RunRecorder(began)
        repeat(seconds) { at -> recorder.second(at.toLong(), service(at)) }
        return recorder.freeze().copy(plan = Plan("paying", listOf("pay"), profile))
    }

    @Test
    fun `three stages split the timeline into three windows whose seconds add up`() {
        val result = ran(
            hold(100.perSecond, over = 2.seconds)
                .then(hold(200.perSecond, over = 2.seconds))
                .then(hold(300.perSecond, over = 2.seconds)),
            seconds = 6,
        ) { 10L }

        val stages = result.stages

        stages.size shouldBe 3
        stages.map { it.index to it.of } shouldBe listOf(0 to 3, 1 to 3, 2 to 3)
        stages.map { it.from } shouldBe listOf(0.seconds, 2.seconds, 4.seconds)
        stages.map { it.until } shouldBe listOf(2.seconds, 4.seconds, 6.seconds)
        withClue("every second of the run is in exactly one stage") {
            stages.sumOf { it.ok + it.failed } shouldBe result.count
        }
    }

    @Test
    fun `each stage carries its own percentiles, which differ from the aggregate`() {
        // The hold is ten times slower than the ramp before it. One p99 over
        // both is a number about neither.
        val result = ran(
            hold(100.perSecond, over = 3.seconds).then(hold(200.perSecond, over = 3.seconds)),
            seconds = 6,
        ) { at -> if (at < 3) 10L else 100L }

        val stages = result.stages

        withClue("the slow half is slower than the fast half, which the aggregate hides") {
            stages[1].serviceTime.p99 shouldBeGreaterThan stages[0].serviceTime.p99
        }
        withClue("and the aggregate sits between them rather than describing either") {
            result["pay"].serviceTime.p99 shouldBeGreaterThan stages[0].serviceTime.p99
        }
    }

    @Test
    fun `a boundary inside a second lands in the earlier stage, and says so`() {
        val result = ran(
            hold(100.perSecond, over = 2500.milliseconds)
                .then(hold(200.perSecond, over = 2500.milliseconds)),
            seconds = 5,
        ) { 10L }

        val stages = result.stages

        withClue("second 2 begins at 2.0s, which is inside the first stage's 2.5s") {
            stages[0].from shouldBe 0.seconds
            stages[0].until shouldBe 3.seconds
        }
        withClue("so the seconds summed are not the window asked for, and both are reported") {
            stages[0].planned shouldBe 2500.milliseconds
            (stages[0].until - stages[0].from) shouldBe 3.seconds
        }
    }

    @Test
    fun `a stage measured at the timeline's width says so rather than a step's`() {
        val result = ran(
            hold(100.perSecond, over = 2.seconds).then(hold(200.perSecond, over = 2.seconds)),
            seconds = 4,
        ) { 10L }

        withClue("a stage is the timeline merged, so it is good to what the timeline is good to") {
            result.stages.first().serviceTime.precision shouldBe result.timeline.first().serviceTime.precision
        }
    }

    @Test
    fun `a run nobody staged has no stages rather than one covering everything`() {
        val result = ran(hold(100.perSecond, over = 4.seconds), seconds = 4) { 10L }

        withClue("one stage would be a table repeating the totals above it") {
            result.stages shouldBe emptyList()
        }
    }

    @Test
    fun `a run with no timeline has no stages to read them off`() {
        val result = ran(
            hold(100.perSecond, over = 2.seconds).then(hold(200.perSecond, over = 2.seconds)),
            seconds = 4,
        ) { 10L }.copy(timeline = emptyList())

        result.stages shouldBe emptyList()
    }
}
