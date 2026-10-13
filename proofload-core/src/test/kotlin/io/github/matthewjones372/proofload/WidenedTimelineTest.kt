package io.github.matthewjones372.proofload

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val began = Instant.parse("2026-10-13T09:00:00Z")

/** Spec 0143: a timeline of a fixed number of slots, whose width doubles as a run outgrows them. */
class WidenedTimelineTest {

    /** One request a second for [over], answering in what [service] says for that second. */
    private fun ran(
        over: Duration,
        slots: Int = RunRecorder.SLOTS,
        service: (Int) -> Duration = { 20.milliseconds },
    ): RunResult {
        val recorder = RunRecorder(began, slots = slots)
        repeat(over.inWholeSeconds.toInt()) { second ->
            // As the engine does once a second, so a day is not a day of live tables.
            recorder.close(before = second.seconds)
            recorder.record(
                step = "pay",
                failure = if (second % 9 == 0) Said("status 503") else null,
                serviceTime = service(second),
                schedulingDelay = (second % 5).milliseconds,
                at = second.seconds + 250.milliseconds,
            )
        }
        return recorder.freeze()
    }

    @Test
    fun `a simulated day holds no more slots than an hour does`() {
        val hour = ran(1.hours)
        val day = ran(1.days)

        hour.timeline shouldHaveSize RunRecorder.SLOTS
        hour.timeline.first().width shouldBe 1.seconds
        withClue("a day is 86,400 seconds, and doubling from one second first fits it in 3,600 slots at 32") {
            day.timeline.size shouldBeLessThanOrEqual hour.timeline.size
            day.timeline.first().width shouldBe 32.seconds
            day.timeline.span shouldBe 1.days
        }
        day.timeline.sumOf { it.count } shouldBe day.count
        day.latePerSecond shouldHaveSize day.timeline.size
    }

    @Test
    fun `a widened slot is the seconds it holds with their buckets added`() {
        val fine = ran(16.seconds, service = { (10 + it).milliseconds })
        val wide = ran(16.seconds, slots = 4, service = { (10 + it).milliseconds })

        wide.timeline.map { it.width } shouldBe List(4) { 4.seconds }
        wide.timeline.map { it.copy(width = 1.seconds) } shouldBe fine.timeline.chunked(4).map { it.added() }
        wide.latePerSecond shouldBe fine.latePerSecond.chunked(4).map { it.merged() }
        wide["pay"].timeline shouldBe wide.timeline
    }

    @Test
    fun `closing slots as a widening run goes freezes into what an unclosed one does`() {
        // Closing on odd seconds leaves a slot half closed when its pair is merged.
        val closing = RunRecorder(began, slots = 6)
        val kept = RunRecorder(began, slots = 6)
        repeat(40) { second ->
            closing.record("pay", null, (5 + second).milliseconds, Duration.ZERO, second.seconds)
            kept.record("pay", null, (5 + second).milliseconds, Duration.ZERO, second.seconds)
            if (second % 3 == 1) closing.close(before = second.seconds)
        }
        withClue("forty seconds in six slots is eight a slot, and only the last is open, for pay and lateness") {
            closing.tablesHeld shouldBe 2
        }

        closing.record("pay", null, 99.milliseconds, 1.milliseconds, 3.seconds)
        kept.record("pay", null, 99.milliseconds, 1.milliseconds, 3.seconds)

        closing.freeze() shouldBe kept.freeze()
        closing.freeze().timeline.first().width shouldBe 8.seconds
    }

    @Test
    fun `merging a widened recorder with one that is not gives the coarser of the two`() {
        val root = RunRecorder(began, slots = 8)
        val wide = root.shard()
        val narrow = root.shard()
        repeat(32) { wide.record("pay", null, 10.milliseconds, Duration.ZERO, it.seconds) }
        repeat(6) { narrow.record("pay", null, 30.milliseconds, Duration.ZERO, it.seconds) }

        val merged = root.shard().apply { merge(narrow); merge(wide) }.freeze()

        merged.timeline.map { it.width }.distinct() shouldBe listOf(4.seconds)
        merged.timeline.map { it.count } shouldBe listOf(8L, 6L, 4L, 4L, 4L, 4L, 4L, 4L)
    }

    @Test
    fun `merging a widened run with one that is not gives the coarser of the two`() {
        val day = ran(1.days)
        val hour = ran(1.hours)

        val merged = Runs(listOf(hour, day)).merged

        withClue("the hour's seconds are added into the day's 32-second slots rather than laid beside them") {
            merged.timeline.map { it.width }.distinct() shouldBe listOf(32.seconds)
            merged.timeline.size shouldBe day.timeline.size
            merged.timeline.sumOf { it.count } shouldBe day.count + hour.count
            merged.timeline.first().count shouldBe 64L
            merged.latePerSecond shouldHaveSize merged.timeline.size
            merged["pay"].timeline shouldBe merged.timeline
        }
    }

    @Test
    fun `a day-long run that warmed up and then held is judged steady, from the start of a slot`() {
        val day = ran(1.days) { second -> if (second < 7_200) 200.milliseconds else (18 + second % 5).milliseconds }

        val settled = day.steadyState.shouldBeInstanceOf<SteadyState.From>()

        withClue("settled at ${settled.offset}") {
            settled.offset shouldBeLessThanOrEqualTo 2.hours
            (settled.offset.inWholeSeconds % 32) shouldBe 0L
        }
        day.steady.timeline.span shouldBe 1.days - settled.offset
        day.steady.count shouldBe day.timeline.drop((settled.offset / 32.seconds).toInt()).sumOf { it.count }
    }

    @Test
    fun `a widened run that got slower says how long its windows were in seconds`() {
        val day = ran(1.days) { second -> if (second < 43_200) 20.milliseconds else 200.milliseconds }

        val state = day.steadyState.shouldBeInstanceOf<SteadyState.NeverSettled>()

        withClue("a quarter of 2,700 slots is 675, of 32 s each") {
            state.why shouldContain "the last 21600 s"
        }
    }

    @Test
    fun `stages read off a widened timeline by the time each slot starts at`() {
        val profile = hold(1.perSecond, over = 12.hours).then(hold(2.perSecond, over = 12.hours))
        val day = ran(1.days) { second -> if (second < 12 * 3600) 10.milliseconds else 100.milliseconds }
            .copy(plan = Plan("paying", listOf("pay"), profile))

        val stages = day.stages

        stages.map { it.from } shouldBe listOf(Duration.ZERO, 12.hours)
        stages.map { it.until } shouldBe listOf(12.hours, 1.days)
        stages.sumOf { it.count } shouldBe day.count
        withClue("the second stage is the slow one") {
            (stages[1].serviceTime.p50 > stages[0].serviceTime.p99) shouldBe true
        }
    }

    @Test
    fun `the schedule a widened run held is counted in seconds, not slots`() {
        val held = ran(1.days)
            .copy(plan = Plan("paying", listOf("pay"), hold(1.perSecond, over = 1.days)))

        held.heldScheduleFor shouldBe 1.days
    }

    @Test
    fun `a slot count under one is refused`() {
        val refusal = shouldThrow<IllegalArgumentException> { RunRecorder(began, slots = 0) }

        refusal.message shouldBe "a timeline holds at least one slot, but slots was 0"
    }

    private fun List<Second>.added(): Second = Second(
        okServiceTime = map { it.okServiceTime }.merged(),
        failedServiceTime = map { it.failedServiceTime }.merged(),
        okResponseTime = map { it.okResponseTime }.merged(),
        failedResponseTime = map { it.failedResponseTime }.merged(),
    )
}
