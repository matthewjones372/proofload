package io.github.matthewjones372.proofload

import io.kotest.matchers.Matcher
import io.kotest.matchers.MatcherResult
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A run that lost its schedule can say when it lost it, which is the number a
 * reader needs to pick the rate to run at next.
 */
class HeldScheduleTest {

    private val startedAt = Instant.parse("2026-08-26T09:00:00Z")

    /** Ten seconds at one departure every 100 ms, late by [late] from [from] on. */
    private fun ran(from: Int, late: Duration): RunResult {
        val recorder = RunRecorder(startedAt)
        repeat(SECONDS * PER_SECOND) { request ->
            val second = request / PER_SECOND
            recorder.record(
                step = "pay",
                failure = null,
                serviceTime = 1.milliseconds,
                schedulingDelay = if (second >= from) late else Duration.ZERO,
                at = (request * MILLIS_PER_REQUEST).milliseconds,
            )
        }
        return recorder.freeze().copy(
            plan = Plan("checkout", listOf("pay"), hold(PER_SECOND.perSecond, over = SECONDS.seconds)),
        )
    }

    @Test
    fun `a run late from the twelfth second held its schedule for eleven`() {
        ran(from = 11, late = 400.milliseconds).heldScheduleFor shouldBe 11.seconds
    }

    @Test
    fun `a run that kept its schedule throughout held it for the whole window`() {
        ran(from = SECONDS, late = Duration.ZERO).heldScheduleFor shouldBe SECONDS.seconds
    }

    @Test
    fun `lateness under a whole planned interval is not losing the schedule`() {
        // A planned interval of 100 ms: 20 ms late every second is a backlog
        // that never grows into a departure, which lostGround already says.
        ran(from = 0, late = 20.milliseconds).heldScheduleFor shouldBe SECONDS.seconds
    }

    @Test
    fun `a run nobody planned held no schedule, and says so rather than zero`() {
        RunResult(startedAt = startedAt, steps = emptyMap(), behind = Timing.none).heldScheduleFor.shouldBeNull()
    }

    @Test
    fun `the lateness of a second is the lateness of that second's departures`() {
        val result = ran(from = 11, late = 400.milliseconds)

        result.latePerSecond[0].p99 shouldBe Duration.ZERO
        // The top of the bucket it landed in, never a point between two: a
        // coarse table is good to 3.1%, and it rounds away from zero.
        result.latePerSecond[11].p99 shouldBe within(400.milliseconds)
        result.latePerSecond.size shouldBe SECONDS
    }

    @Test
    fun `two shards' seconds line up when they merge`() {
        val root = RunRecorder(startedAt)
        val shard = root.shard()
        root.record("pay", null, 1.milliseconds, 300.milliseconds, at = 3.seconds)
        shard.record("pay", null, 1.milliseconds, 500.milliseconds, at = 3.seconds)

        root.merge(shard)

        val third = root.freeze().latePerSecond[3]
        third.count shouldBe 2L
        third.max shouldBe within(500.milliseconds)
    }

    /** A duration at or just above [asked], by no more than the coarse table's own precision. */
    private fun within(asked: Duration): Matcher<Duration> = object : Matcher<Duration> {
        override fun test(value: Duration): MatcherResult = MatcherResult(
            value >= asked && value <= asked * (1.0 + Histogram.COARSE_PRECISION),
            { "$value is not $asked to within ${Histogram.COARSE_PRECISION}" },
            { "$value is $asked to within ${Histogram.COARSE_PRECISION}" },
        )
    }

    private companion object {
        const val SECONDS = 20
        const val PER_SECOND = 10
        const val MILLIS_PER_REQUEST = 100L
    }
}
