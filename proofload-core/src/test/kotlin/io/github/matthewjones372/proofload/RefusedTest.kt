package io.github.matthewjones372.proofload

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val began = Instant.parse("2026-10-15T09:00:00Z")

/** Spec 0144: refused arrivals are counted by slot, and no view of a run loses them. */
class RefusedTest {

    /** One request a second for [over], and [refused] arrivals refused in each second. */
    private fun ran(over: Duration, slots: Int = RunRecorder.SLOTS, refused: (Int) -> Int): RunResult {
        val recorder = RunRecorder(began, slots = slots)
        repeat(over.inWholeSeconds.toInt()) { second ->
            recorder.record("pay", null, 20.milliseconds, Duration.ZERO, second.seconds)
            repeat(refused(second)) { recorder.refused(at = second.seconds + 100.milliseconds) }
        }
        return recorder.freeze()
    }

    @Test
    fun `refusals are counted in the second they were due in, and add up to the total`() {
        val result = ran(4.seconds) { it }

        result.refusedPerSecond shouldBe listOf(0L, 1L, 2L, 3L)
        result.refused shouldBe 6L
    }

    @Test
    fun `a widened slot adds the refusals of the seconds it holds`() {
        val wide = ran(16.seconds, slots = 4) { 1 }

        wide.refusedPerSecond shouldBe List(4) { 4L }
    }

    @Test
    fun `merged runs add their refusals rather than dropping them`() {
        val one = ran(2.seconds) { 1 }
        val other = ran(2.seconds) { 2 }

        Runs(listOf(one, other)).merged.refusedPerSecond shouldBe listOf(3L, 3L)
    }

    @Test
    fun `a stage keeps the refusals of its own seconds`() {
        val result = ran(4.seconds) { it }

        val second = Stage(
            index = 1,
            of = 2,
            profile = hold(1.perSecond, over = 2.seconds),
            from = 2.seconds,
            until = 4.seconds,
            planned = 2.seconds,
            serviceTime = Timing.none,
            responseTime = Timing.none,
            ok = 2L,
            failed = 0L,
        )

        result.during(second).refused shouldBe 5L
    }

    @Test
    fun `a run that refused nothing reads zero, not a list of zeroes`() {
        val result = ran(3.seconds) { 0 }

        result.refused shouldBe 0L
        result.refusedPerSecond shouldBe emptyList()
    }

    @Test
    fun `two simulations sent together share the stricter cap`() {
        val pay = scenario("pay") { exec("pay") { } }
        val browse = scenario("browse") { exec("browse") { } }

        val both = pay.at(10.perSecond, over = 1.seconds).inFlightAtMost(50) +
            browse.at(10.perSecond, over = 1.seconds).inFlightAtMost(20)

        both.inFlightCap shouldBe 20
    }

    @Test
    fun `a cap of nobody is refused`() {
        val pay = scenario("pay") { exec("pay") { } }

        assertThrows<IllegalArgumentException> { pay.at(10.perSecond, over = 1.seconds).inFlightAtMost(0) }
    }
}
