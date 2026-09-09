package io.github.matthewjones372.proofload

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * A run that ran out of descriptors was measuring itself. Saying so is the
 * difference between a failure counted against the target and one explained.
 */
class LimitsTest {

    private fun resultWith(limits: Limits) = RunResult(
        startedAt = Instant.parse("2026-08-26T09:00:00Z"),
        steps = emptyMap(),
        behind = Timing.none,
        limits = limits,
    )

    @Test
    fun `a run that sampled nothing has not run out of room`() {
        resultWith(Limits.none).ranOutOfRoom() shouldBe false
    }

    @Test
    fun `a reading at nine tenths of its limit has run out of room, and one at four does not`() {
        resultWith(Limits(openFiles = Headroom.Measured(peak = 61_120, limit = 65_536))).ranOutOfRoom() shouldBe true
        resultWith(Limits(openFiles = Headroom.Measured(peak = 26_000, limit = 65_536))).ranOutOfRoom() shouldBe false
    }

    @Test
    fun `ports count as well as descriptors`() {
        resultWith(Limits(ports = Headroom.Measured(peak = 27_998, limit = 28_232))).ranOutOfRoom() shouldBe true
    }

    @Test
    fun `a saturated CPU is reported and never votes`() {
        // The generator and the target share every core on a laptop and on a
        // single CI runner, so a share near saturation is the ordinary case.
        resultWith(Limits(cpu = Headroom.Measured(peak = 400, limit = 400))).ranOutOfRoom() shouldBe false
    }

    @Test
    fun `the share of a ceiling a run reached is readable`() {
        Headroom.Measured(peak = 61_120, limit = 65_536).used shouldBe 0.9326.plusOrMinus(0.001)
    }

    @Test
    fun `a limit of zero is refused rather than divided by`() {
        shouldThrow<IllegalArgumentException> { Headroom.Measured(peak = 1, limit = 0) }
    }

    @Test
    fun `a merged set carries the worst any of its runs came to a ceiling`() {
        val comfortable = resultWith(Limits(openFiles = Headroom.Measured(peak = 100, limit = 65_536)))
        val tight = resultWith(Limits(openFiles = Headroom.Measured(peak = 61_120, limit = 65_536)))

        val merged = Runs.of(comfortable, tight, comfortable).merged

        merged.limits.openFiles shouldBe Headroom.Measured(peak = 61_120, limit = 65_536)
        merged.ranOutOfRoom() shouldBe true
    }

    @Test
    fun `a set where nothing was sampled still says nothing was sampled`() {
        Runs.of(resultWith(Limits.none), resultWith(Limits.none)).merged.limits.openFiles
            .shouldBeInstanceOf<Headroom.Absent>()
    }

    @Test
    fun `a rung whose injector ran out of room is void, not failed`() {
        val ranOut = Rung(
            rate = 100.perSecond,
            result = resultWith(Limits(ports = Headroom.Measured(peak = 27_998, limit = 28_232))),
        )

        ranOut.outcome shouldBe Rung.Outcome.Void
    }

    @Test
    fun `a rung with room to spare is judged on its goals as before`() {
        val comfortable = Rung(
            rate = 100.perSecond,
            result = resultWith(Limits(ports = Headroom.Measured(peak = 100, limit = 28_232))),
        )

        comfortable.outcome shouldBe Rung.Outcome.Passed
    }

    @Test
    fun `the rung line names which ceiling it hit, since the two are fixed differently`() {
        val ranOut = Rung(
            rate = 100.perSecond,
            result = resultWith(Limits(openFiles = Headroom.Measured(peak = 61_120, limit = 65_536))),
        )

        val printed = printed { Progress.lines().climbed(ranOut, number = 1, atMost = 1.seconds) }

        printed shouldContain "the injector ran out of open files"
    }

    private fun printed(block: () -> Unit): String {
        val out = java.io.ByteArrayOutputStream()
        val before = System.out
        System.setOut(java.io.PrintStream(out))
        try {
            block()
        } finally {
            System.setOut(before)
        }
        return out.toString()
    }

    @Test
    fun `a merged set adds the users each run had in flight in that second`() {
        val one = resultWith(Limits.none).copy(usersInFlight = listOf(5L, 6L, null))
        val other = resultWith(Limits.none).copy(usersInFlight = listOf(4L, null, null))

        val merged = Runs.of(one, other).merged

        merged.usersInFlight shouldBe listOf(9L, 6L, null)
    }
}
