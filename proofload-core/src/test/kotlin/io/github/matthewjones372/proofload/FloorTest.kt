package io.github.matthewjones372.proofload

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

class FloorTest {

    @Test
    fun `a machine that measured the same thing twice the same way can resolve anything`() {
        resolutionOf(listOf(100.milliseconds, 100.milliseconds, 100.milliseconds)) shouldBe 0.0
    }

    @Test
    fun `the floor is the spread of the repeats as a fraction of the middle one`() {
        val floor = resolutionOf(listOf(100.milliseconds, 103.milliseconds, 106.milliseconds))

        withClue("6 ms of spread around a 103 ms middle: $floor") {
            floor shouldBeGreaterThan 0.057
            floor shouldBeLessThan 0.059
        }
    }

    @Test
    fun `a floor is relative, so the same spread on a slower machine is a smaller number`() {
        val fast = resolutionOf(listOf(10.milliseconds, 20.milliseconds))
        val slow = resolutionOf(listOf(1000.milliseconds, 1010.milliseconds))

        withClue("ten milliseconds apart in both cases: fast $fast, slow $slow") {
            fast shouldBeGreaterThan slow
        }
    }

    /**
     * The mechanism by which a busy machine reports a worse floor: the repeats
     * land further apart, and nothing else has to change for the number to say
     * so.
     */
    @Test
    fun `repeats that landed further apart are a larger floor`() {
        val steady = resolutionOf(listOf(100.milliseconds, 101.milliseconds, 102.milliseconds))
        val scattered = resolutionOf(listOf(80.milliseconds, 101.milliseconds, 140.milliseconds))

        withClue("the same middle, twice the spread: steady $steady, scattered $scattered") {
            scattered shouldBeGreaterThan steady
        }
    }

    @Test
    fun `one measurement is not a spread`() {
        shouldThrow<IllegalArgumentException> { resolutionOf(listOf(1.milliseconds)) }
    }

    @Test
    fun `a fraction of nothing is not a floor`() {
        shouldThrow<IllegalArgumentException> { resolutionOf(listOf(Duration.ZERO, Duration.ZERO)) }
    }

    @Test
    fun `the spread in duration is the fraction multiplied back out by what it was a fraction of`() {
        val loaded = Floor(resolution = 1.25, hiccups = Timing.none, probe = Probe(48.microseconds))

        withClue("125% of the 48us the repeats measured: ${loaded.absolute}") {
            loaded.absolute shouldBe 60.microseconds
        }
    }

    @Test
    fun `a floor somebody named rather than measured has no spread in duration to give`() {
        Floor(resolution = 0.061, hiccups = Timing.none).absolute shouldBe Duration.ZERO
    }

    /**
     * The mistake this exists to stop. One floor refuses a claim at the
     * magnitude it was taken at and allows one three orders larger, because the
     * movement behind both is the same handful of microseconds.
     */
    @Test
    fun `a fraction is judged at the magnitude the claim is made at, not the one the floor was taken at`() {
        val loaded = Floor(resolution = 1.25, hiccups = Timing.none, probe = Probe(48.microseconds))

        withClue("4% of 250 ms is 10 ms, well past the ${loaded.absolute} this machine moved by") {
            loaded.resolves(0.04, of = 250.milliseconds) shouldBe true
        }
        withClue("4% of the probe's own magnitude is 1.9us, which is inside it") {
            loaded.resolves(0.04, of = 48.microseconds) shouldBe false
        }
    }

    @Test
    fun `a difference smaller than the machine's own movement is the machine, whichever way it went`() {
        val floor = Floor(resolution = 0.061, hiccups = Timing.none, probe = Probe(100.milliseconds))

        floor.resolves(0.02, of = 100.milliseconds) shouldBe false
        floor.resolves(-0.02, of = 100.milliseconds) shouldBe false
        floor.resolves(0.09, of = 100.milliseconds) shouldBe true
        withClue("a difference exactly at the floor is not above it") {
            floor.resolves(0.061, of = 100.milliseconds) shouldBe false
        }
    }

    /**
     * Per claim rather than per machine, because there is no machine-wide
     * answer to give: the movement that swamps a null step is nothing at a
     * quarter of a second, and both claims are made on the same machine.
     */
    @Test
    fun `a machine that moves by two fifths of what is being claimed cannot support that claim`() {
        val loaded = Floor(resolution = 1.25, hiccups = Timing.none, probe = Probe(48.microseconds))

        loaded.supports(100.microseconds) shouldBe false
        loaded.supports(250.milliseconds) shouldBe true
    }

    @Test
    fun `a change clears the machine only when it clears the repeats and the stalls both`() {
        val floor = Floor(
            resolution = 0.01,
            hiccups = Timing.none.copy(count = 100L, p99 = 2.milliseconds),
            probe = Probe(100.milliseconds),
        )

        withClue("3 ms clears both the 1 ms the repeats moved and the 2 ms the injector stalled for") {
            floor.separates(20.milliseconds, 23.milliseconds) shouldBe true
        }
        withClue("1.5 ms clears the repeats and not the stalls, so it is the measuring process") {
            floor.separates(20.milliseconds, 21.5.milliseconds) shouldBe false
        }
        withClue("a change is a change whichever way it went") {
            floor.separates(23.milliseconds, 20.milliseconds) shouldBe true
        }
    }
}
