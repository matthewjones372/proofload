package io.github.matthewjones372.proofload

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val began = Instant.parse("2026-10-13T09:00:00Z")

/** Spec 0143: the users in flight are sampled into the timeline's slots, and widen with them. */
class InFlightSlotsTest {

    /** [rate] requests a second for [over], each taking [took], with [users] counted each second. */
    private fun ran(
        over: Duration,
        slots: Int = RunRecorder.SLOTS,
        rate: Int = 1,
        took: Duration = 20.milliseconds,
        users: (Int) -> Long = { 1L },
    ): RunResult {
        val recorder = RunRecorder(began, slots = slots)
        repeat(over.inWholeSeconds.toInt()) { second ->
            recorder.close(before = second.seconds)
            recorder.inFlight(at = second.seconds + 500.milliseconds, users = users(second))
            repeat(rate) { request ->
                val at = second.seconds + (request * 1_000 / rate).milliseconds
                recorder.record("pay", null, took, Duration.ZERO, at)
            }
        }
        return recorder.freeze()
    }

    @Test
    fun `a day of samples holds no more readings than an hour does`() {
        val hour = ran(1.hours)
        val day = ran(1.days)

        hour.usersInFlight shouldHaveSize RunRecorder.SLOTS
        day.usersInFlight shouldHaveSize day.timeline.size
    }

    @Test
    fun `a widened slot keeps the first reading taken in it`() {
        val wide = ran(16.seconds, slots = 4, users = { it.toLong() })

        wide.timeline.map { it.width }.distinct() shouldBe listOf(4.seconds)
        wide.usersInFlight shouldBe listOf(0L, 4L, 8L, 12L)
    }

    @Test
    fun `merging a widened run with one that is not keeps one reading a slot of the coarser`() {
        val wide = ran(16.seconds, slots = 4, users = { 1L })
        val narrow = ran(16.seconds, users = { 2L })

        Runs(listOf(narrow, wide)).merged.usersInFlight shouldBe List(4) { 3L }
    }

    @Test
    fun `the law is asked of a widened run over the slots it settled into`() {
        // Ten a second at 500 ms is five users in flight, for a day.
        val day = ran(1.days, rate = 10, took = 500.milliseconds, users = { 5L })
            .copy(plan = Plan("paying", listOf("pay"), hold(10.perSecond, over = 1.days)))

        val law = day.concurrency.shouldBeInstanceOf<Concurrency.Measured>()

        withClue("observed ${law.observed} against predicted ${law.fromServiceTime}") {
            law.observed shouldBe 5.0
            law.fromServiceTime shouldBe 5.0.plusOrMinus(0.1)
            law.agrees shouldBe true
            law.samples shouldBe day.steady.timeline.size
        }
    }
}
