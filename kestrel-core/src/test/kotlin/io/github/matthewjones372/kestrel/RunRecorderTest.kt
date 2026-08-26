package io.github.matthewjones372.kestrel

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

private val started = Instant.parse("2026-08-26T09:00:00Z")

class RunRecorderTest {

    private fun RunRecorder.pay(failure: String? = null, service: Long = 10L, late: Long = 0L) =
        record(
            step = "pay",
            failure = failure,
            serviceTime = service.milliseconds,
            schedulingDelay = late.milliseconds,
        )

    @Test
    fun `a recorder counts what it was given`() {
        val recorder = RunRecorder(started)
        repeat(3) { recorder.pay() }
        recorder.pay(failure = "status 503")

        val result = recorder.freeze()

        result["pay"].count shouldBe 4L
        result["pay"].ok shouldBe 3L
        result["pay"].failures shouldBe mapOf("status 503" to 1L)
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
        repeat(5) { right.pay(failure = "timeout", service = 50L); both.pay(failure = "timeout", service = 50L) }

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
        repeat(RunRecorder.MAX_REASONS_PER_STEP + 5) { recorder.pay(failure = "order $it rejected") }

        val failures = recorder.freeze()["pay"].failures

        failures.size shouldBe RunRecorder.MAX_REASONS_PER_STEP + 1
        failures[RunRecorder.OTHER_REASONS] shouldBe 5L
    }

    private fun Map<String, StepStats>.shouldBeEmptyMap() = isEmpty() shouldBe true
}
