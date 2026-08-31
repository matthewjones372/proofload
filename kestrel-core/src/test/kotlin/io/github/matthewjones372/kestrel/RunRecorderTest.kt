package io.github.matthewjones372.kestrel

import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

private val started = Instant.parse("2026-08-26T09:00:00Z")

class RunRecorderTest {

    private fun RunRecorder.pay(failure: Reason? = null, service: Long = 10L, late: Long = 0L, at: Long = 0L) =
        record(
            step = "pay",
            failure = failure,
            serviceTime = service.milliseconds,
            schedulingDelay = late.milliseconds,
            at = at.milliseconds,
        )

    @Test
    fun `a recorder counts what it was given`() {
        val recorder = RunRecorder(started)
        repeat(3) { recorder.pay() }
        recorder.pay(failure = Said("status 503"))

        val result = recorder.freeze()

        result["pay"].count shouldBe 4L
        result["pay"].ok.count shouldBe 3L
        result["pay"].failed.count shouldBe 1L
        result["pay"].failed.reasons shouldBe mapOf(Said("status 503") to 1L)
    }

    @Test
    fun `a target that sheds load fast reports a low failed p99 and the successes keep their own`() {
        val recorder = RunRecorder(started)
        repeat(90) { recorder.pay(failure = Said("status 503"), service = 1L) }
        repeat(10) { recorder.pay(service = 500L) }

        val pay = recorder.freeze()["pay"]

        pay.failed.serviceTime.p99 shouldBeLessThan 10.milliseconds
        pay.ok.serviceTime.p99 shouldBeGreaterThanOrEqualTo 500.milliseconds
        withClue("the whole step counts both, so its median is the rejections' rather than anybody's") {
            pay.serviceTime.p50 shouldBeLessThan 10.milliseconds
        }
    }

    @Test
    fun `the whole step's timing is the merge of the two sides`() {
        val recorder = RunRecorder(started)
        repeat(3) { recorder.pay(service = 20L, late = 5L) }
        repeat(2) { recorder.pay(failure = TimedOut, service = 700L, late = 5L) }

        val pay = recorder.freeze()["pay"]

        pay.serviceTime shouldBe merged(pay.ok.serviceTime, pay.failed.serviceTime)
        pay.responseTime shouldBe merged(pay.ok.responseTime, pay.failed.responseTime)
    }

    @Test
    fun `response time is service time plus the wait the generator caused`() {
        val recorder = RunRecorder(started)
        recorder.pay(service = 10L, late = 90L)

        val result = recorder.freeze()

        (result["pay"].responseTime.max >= 100.milliseconds) shouldBe true
        (result["pay"].serviceTime.max < 20.milliseconds) shouldBe true
        (result.behind.max >= 90.milliseconds) shouldBe true
    }

    @Test
    fun `two recorders merged say what one recorder given both would say`() {
        val left = RunRecorder(started)
        val right = RunRecorder(started)
        val both = RunRecorder(started)
        repeat(5) { left.pay(service = 10L); both.pay(service = 10L) }
        repeat(5) { right.pay(failure = TimedOut, service = 50L); both.pay(failure = TimedOut, service = 50L) }

        left.merge(right)

        left.freeze() shouldBe both.freeze()
    }

    @Test
    fun `a run with no traffic freezes into a result that says so`() {
        val result = RunRecorder(started).freeze()

        result.count shouldBe 0L
        result.steps.shouldBeEmptyMap()
        result.startedAt shouldBe started
    }

    @Test
    fun `reasons that interpolate an id are capped, and the cap is in the result`() {
        val recorder = RunRecorder(started)
        repeat(RunRecorder.MAX_REASONS_PER_STEP + 5) { recorder.pay(failure = Said("order $it rejected")) }

        val failures = recorder.freeze()["pay"].failed.reasons

        failures.size shouldBe RunRecorder.MAX_REASONS_PER_STEP + 1
        failures[Other] shouldBe 5L
    }

    private fun Map<String, StepStats>.shouldBeEmptyMap() = isEmpty() shouldBe true

    /** Every sample of both sides put through one histogram, which is what the whole step claims to be. */
    private fun merged(left: Timing, right: Timing): Timing =
        Histogram().apply {
            (left.distribution + right.distribution).forEach { bucket ->
                repeat(bucket.count.toInt()) { record(bucket.upperBound) }
            }
        }.timing()
}
