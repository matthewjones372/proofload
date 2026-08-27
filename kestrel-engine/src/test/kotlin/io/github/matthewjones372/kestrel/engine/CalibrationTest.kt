package io.github.matthewjones372.kestrel.engine

import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class CalibrationTest {

    @Test
    fun `a machine nobody has characterised has no floor to read`() {
        declaredFloor().shouldBeNull()
    }

    @Test
    fun `a floor somebody has already measured is named rather than measured again`() {
        System.setProperty(RESOLUTION_PROPERTY, "0.02")
        try {
            declaredFloor()?.resolution shouldBe 0.02
        } finally {
            System.clearProperty(RESOLUTION_PROPERTY)
        }
    }

    @Test
    fun `text that is not a fraction is not a floor either`() {
        System.setProperty(RESOLUTION_PROPERTY, "quite noisy")
        try {
            declaredFloor().shouldBeNull()
        } finally {
            System.clearProperty(RESOLUTION_PROPERTY)
        }
    }

    /**
     * Not what the number is — that is the machine's to say, and this one is
     * sharing a build. What it must be is a fraction of repeats that happened,
     * beside stalls that were watched for rather than assumed.
     */
    @Test
    fun `calibrating measures repeats of a null step and what the injector stalled for meanwhile`() {
        val floor = calibrate(within = 4.seconds)

        withClue("a spread is never negative: ${floor.resolution}") {
            floor.resolution shouldBeGreaterThanOrEqual 0.0
        }
        withClue("and it is a number, not an infinity off a zero denominator") {
            floor.resolution.isFinite() shouldBe true
        }
        withClue("the floor carries stalls it watched for: ${floor.hiccups.count} ticks") {
            (floor.hiccups.count > 0L) shouldBe true
        }
    }
}
