package io.github.matthewjones372.kestrel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant

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
}
