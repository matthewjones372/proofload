package io.github.matthewjones372.kestrel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val began = Instant.parse("2026-08-27T09:00:00Z")

/**
 * The timeline as a function of what was recorded and when it was recorded,
 * with no clock in it: the seconds a run reports are decided by the offsets it
 * was handed, so every claim here is deterministic.
 */
class TimelineTest {

    private fun RunRecorder.pay(at: Duration, failure: String? = null, service: Duration = 10.milliseconds) =
        record(step = "pay", failure = failure, serviceTime = service, schedulingDelay = Duration.ZERO, at = at)

    @Test
    fun `a four-second run reports four seconds`() {
        val recorder = RunRecorder(began)
        listOf(0.1, 1.2, 2.3, 3.4).forEach { recorder.pay(at = it.seconds) }

        recorder.freeze().timeline.size shouldBe 4
    }

    @Test
    fun `a second nothing ran in is present and zero rather than missing`() {
        val recorder = RunRecorder(began)
        recorder.pay(at = 0.5.seconds)
        recorder.pay(at = 3.5.seconds)

        val timeline = recorder.freeze().timeline

        timeline.size shouldBe 4
        withClue("the quiet seconds") {
            timeline[1].count shouldBe 0L
            timeline[2].count shouldBe 0L
            timeline[1].p99 shouldBe Duration.ZERO
        }
    }

    @Test
    fun `the seconds count every request the run counted`() {
        val recorder = RunRecorder(began)
        repeat(30) { recorder.pay(at = (it * 100).milliseconds) }

        val result = recorder.freeze()

        result.timeline.sumOf { it.count } shouldBe result.count
        result.timeline.sumOf { it.ok } shouldBe result.ok
    }

    @Test
    fun `seconds are counted from the run's start, so the first is a full second of load`() {
        val recorder = RunRecorder(began)
        recorder.pay(at = 999.milliseconds)
        recorder.pay(at = 1_000.milliseconds)

        val timeline = recorder.freeze().timeline

        timeline[0].count shouldBe 1L
        timeline[1].count shouldBe 1L
    }

    @Test
    fun `a failure is counted in the second it happened in`() {
        val recorder = RunRecorder(began)
        recorder.pay(at = 0.5.seconds)
        recorder.pay(at = 1.5.seconds, failure = "status 503")

        val timeline = recorder.freeze().timeline

        timeline[0].failed shouldBe 0L
        timeline[1].failed shouldBe 1L
        timeline[1].ok shouldBe 0L
    }

    @Test
    fun `two recorders merged report the timeline one given both would`() {
        val left = RunRecorder(began)
        val right = RunRecorder(began)
        val both = RunRecorder(began)
        listOf(0.1, 2.1).forEach { left.pay(at = it.seconds); both.pay(at = it.seconds) }
        listOf(1.1, 3.1).forEach { right.pay(at = it.seconds); both.pay(at = it.seconds) }

        left.merge(right)

        left.freeze().timeline shouldBe both.freeze().timeline
    }

    @Test
    fun `a step keeps its own seconds, and the run's count every step`() {
        val recorder = RunRecorder(began)
        recorder.pay(at = 0.5.seconds)
        recorder.record(
            step = "browse",
            failure = null,
            serviceTime = 10.milliseconds,
            schedulingDelay = Duration.ZERO,
            at = 0.5.seconds,
        )

        val result = recorder.freeze()

        result["pay"].timeline[0].count shouldBe 1L
        result["browse"].timeline[0].count shouldBe 1L
        result.timeline[0].count shouldBe 2L
    }

    @Test
    fun `a target that got slower halfway through says so second by second`() {
        val recorder = RunRecorder(began)
        repeat(50) { recorder.pay(at = (it * 20).milliseconds, service = 10.milliseconds) }
        repeat(50) { recorder.pay(at = (2_000 + it * 20).milliseconds, service = 400.milliseconds) }

        val timeline = recorder.freeze().timeline

        withClue("first second ${timeline[0].p99}, third second ${timeline[2].p99}") {
            (timeline[0].p99 < 20.milliseconds) shouldBe true
            (timeline[2].p99 >= 400.milliseconds) shouldBe true
        }
    }

    @Test
    fun `a request that left before the run began is a bug in the caller`() {
        shouldThrow<IllegalArgumentException> { RunRecorder(began).pay(at = (-1).milliseconds) }
    }

    @Test
    fun `a run that recorded nothing has no seconds to report`() {
        RunRecorder(began).freeze().timeline shouldBe emptyList()
    }
}
