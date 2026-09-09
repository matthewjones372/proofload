package io.github.matthewjones372.proofload

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A run that could not keep its schedule still measured something. What it
 * measured is the target at the load that left, and this is that load.
 */
class OfferedTest {

    private val startedAt = Instant.parse("2026-08-26T09:00:00Z")

    /** A run asked for 2,500 a second over 20 seconds that took 26 to send them. */
    private fun behindByEight(): RunResult {
        val recorder = RunRecorder(startedAt)
        repeat(SENT) { request ->
            recorder.record(
                step = "pay",
                failure = null,
                serviceTime = 1.milliseconds,
                schedulingDelay = Duration.ZERO,
                at = (request * TOOK.inWholeMilliseconds / SENT).milliseconds,
            )
        }
        return recorder.freeze().copy(
            plan = Plan("checkout", listOf("pay"), hold(2_500.perSecond, over = 20.seconds)),
        )
    }

    @Test
    fun `a run that fell behind says what left, and over how long`() {
        val offered = behindByEight().offered.shouldNotBeNull()

        offered.asked.perSecond shouldBe 2_500.0
        // 50,000 requests over 26 seconds: every request the plan asked for
        // left, and the schedule took six seconds longer than it promised.
        offered.left.perSecond shouldBe 1_923.0.plusOrMinus(1.0)
        offered.over shouldBe TOOK
    }

    @Test
    fun `the share that left is the fraction a reader compares the numbers against`() {
        val offered = behindByEight().offered.shouldNotBeNull()

        offered.share shouldBe (20.0 / TOOK.inWholeSeconds.toDouble()).plusOrMinus(0.01)
        offered.share shouldBeGreaterThan 0.0
    }

    @Test
    fun `a result nobody planned offers nothing rather than zero`() {
        RunResult(startedAt = startedAt, steps = emptyMap(), behind = Timing.none).offered.shouldBeNull()
    }

    @Test
    fun `a planned run with no timeline offers nothing rather than zero`() {
        val planned = RunResult(
            startedAt = startedAt,
            steps = emptyMap(),
            behind = Timing.none,
            plan = Plan("checkout", listOf("pay"), hold(100.perSecond, over = 10.seconds)),
        )

        planned.offered.shouldBeNull()
    }

    private companion object {
        const val SENT = 50_000
        val TOOK = 26.seconds
    }
}
