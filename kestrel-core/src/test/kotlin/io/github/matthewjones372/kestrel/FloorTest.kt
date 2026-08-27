package io.github.matthewjones372.kestrel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration
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
    fun `a difference smaller than the floor is the machine, whatever else it looks like`() {
        val floor = Floor(resolution = 0.061, hiccups = Timing.none)

        floor.resolves(0.02) shouldBe false
        floor.resolves(-0.02) shouldBe false
        floor.resolves(0.09) shouldBe true
        withClue("a difference exactly at the floor is not above it") {
            floor.resolves(0.061) shouldBe false
        }
    }

    @Test
    fun `a machine that moves by two fifths of itself between identical runs supports no latency claim`() {
        Floor(resolution = 0.40, hiccups = Timing.none).supportsAClaim shouldBe false
        Floor(resolution = 0.061, hiccups = Timing.none).supportsAClaim shouldBe true
    }
}
