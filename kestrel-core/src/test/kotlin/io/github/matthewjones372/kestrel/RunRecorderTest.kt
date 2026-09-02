package io.github.matthewjones372.kestrel

import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
    fun `a step counts a user once, however many requests that user makes under it`() {
        val recorder = RunRecorder(started)
        recorder.record("poll", null, 10.milliseconds, Duration.ZERO, Duration.ZERO, reached = true)
        repeat(2) { recorder.record("poll", null, 10.milliseconds, Duration.ZERO, Duration.ZERO, reached = false) }

        val poll = recorder.freeze()["poll"]

        poll.count shouldBe 3L
        poll.reached shouldBe 1L
    }

    @Test
    fun `the users two shards saw are added together when they merge`() {
        val one = RunRecorder(started).apply {
            record("poll", null, 10.milliseconds, Duration.ZERO, Duration.ZERO, reached = true)
        }
        val other = RunRecorder(started).apply {
            record("poll", null, 10.milliseconds, Duration.ZERO, Duration.ZERO, reached = true)
        }

        one.merge(other)

        one.freeze()["poll"].reached shouldBe 2L
    }

    @Test
    fun `a recorder nobody told about users reports none rather than a number it made up`() {
        val recorder = RunRecorder(started)
        repeat(3) { recorder.pay() }

        recorder.freeze()["pay"].reached shouldBe 0L
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

    @Test
    fun `a recorder answers offsets from its own origin`() {
        // A run that began two seconds ago, stated rather than waited for.
        val recorder = RunRecorder(started, origin = System.nanoTime() - 2.seconds.inWholeNanoseconds)

        recorder.sinceStart() shouldBeGreaterThanOrEqualTo 2.seconds
    }

    @Test
    fun `a shard measures from the origin of the recorder that spawned it`() {
        val root = RunRecorder(started, origin = 0L)

        // A shard made later must not start its own clock: two zero points
        // would pool two timelines that disagree about when second 0 was.
        val shard = root.shard()

        (shard.sinceStart() - root.sinceStart()).absoluteValue shouldBeLessThan 50.milliseconds
    }

    @Test
    fun `a shard records into the same seconds as the recorder it came from`() {
        val root = RunRecorder(started, origin = 0L)
        val shard = root.shard()
        root.pay(at = 1_500L)
        shard.pay(at = 1_500L)

        root.merge(shard)

        // Both landed in second 1, so the merged timeline has two there.
        root.freeze().timeline[1].count shouldBe 2L
    }
}
