package io.github.matthewjones372.proofload

import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * L = λW is arithmetic, not a model: over a window that starts and ends empty
 * it holds whatever the arrivals and whatever the target. So the two sides
 * disagreeing is a fact about the measurement rather than about the target.
 */
class ConcurrencyTest {

    private val startedAt = Instant.parse("2026-08-26T09:00:00Z")

    /**
     * A run of [seconds] seconds at [rate] a second where every request took
     * [took], with [inFlight] users sampled each second.
     */
    private fun ran(
        rate: Int,
        took: Duration,
        inFlight: Long,
        seconds: Int = 12,
        pauses: Boolean = false,
    ): RunResult {
        val recorder = RunRecorder(startedAt)
        repeat(seconds * rate) { request ->
            recorder.record(
                step = "pay",
                failure = null,
                serviceTime = took,
                schedulingDelay = Duration.ZERO,
                at = (request / rate).seconds,
            )
        }
        return recorder.freeze().copy(
            plan = Plan(
                listOf(
                    PlannedArm("checkout", listOf("pay"), hold(rate.perSecond, over = seconds.seconds), pauses),
                ),
            ),
            usersInFlight = List(seconds) { inFlight },
        )
    }

    @Test
    fun `quantities that agree report the law holding`() {
        // 100 a second at 50 ms is five users in flight.
        val law = ran(rate = 100, took = 50.milliseconds, inFlight = 5L).concurrency
            .shouldBeInstanceOf<Concurrency.Measured>()

        withClue("observed ${law.observed} against predicted ${law.fromServiceTime}") {
            law.fromServiceTime shouldBe 5.0.plusOrMinus(0.1)
            law.ratio shouldBe 1.0.plusOrMinus(LAW_TOLERANCE)
            law.agrees shouldBe true
        }
    }

    @Test
    fun `a latency twice what the count says reports the ratio and does not agree`() {
        // The same five users, but every request took twice as long: the
        // prediction is ten and the count still says five.
        val law = ran(rate = 100, took = 100.milliseconds, inFlight = 5L).concurrency
            .shouldBeInstanceOf<Concurrency.Measured>()

        law.ratio shouldBe 0.5.plusOrMinus(0.05)
        law.agrees shouldBe false
    }

    @Test
    fun `a scenario that parks its users cannot be asked, and says why`() {
        val law = ran(rate = 100, took = 50.milliseconds, inFlight = 5L, pauses = true).concurrency

        law.shouldBeInstanceOf<Concurrency.Absent>().because shouldContain "parks its users"
    }

    @Test
    fun `a run that sampled no users says so rather than reporting a ratio`() {
        val law = ran(rate = 100, took = 50.milliseconds, inFlight = 5L).copy(usersInFlight = emptyList())
            .concurrency

        law.shouldBeInstanceOf<Concurrency.Absent>().because shouldContain "no user count was sampled"
    }

    @Test
    fun `a result nobody ran has no window to ask over`() {
        val law = RunResult(startedAt = startedAt, steps = emptyMap(), behind = Timing.none).concurrency

        law.shouldBeInstanceOf<Concurrency.Absent>().because shouldContain "second by second"
    }

    @Test
    fun `the backlog is the difference the two clocks make`() {
        val recorder = RunRecorder(startedAt)
        repeat(1_200) { request ->
            recorder.record(
                step = "pay",
                failure = null,
                serviceTime = 50.milliseconds,
                schedulingDelay = 50.milliseconds,
                at = (request / 100).seconds,
            )
        }
        val result = recorder.freeze().copy(
            plan = Plan("checkout", listOf("pay"), hold(100.perSecond, over = 12.seconds)),
            usersInFlight = List(12) { 5L },
        )

        val law = result.concurrency.shouldBeInstanceOf<Concurrency.Measured>()

        withClue("100 a second held up by 50 ms is five requests of queue") {
            law.backlog shouldBe 5.0.plusOrMinus(0.2)
        }
    }
}
