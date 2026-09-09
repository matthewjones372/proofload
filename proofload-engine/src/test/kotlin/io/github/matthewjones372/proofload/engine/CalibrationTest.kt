package io.github.matthewjones372.proofload.engine

import io.github.matthewjones372.proofload.Floor
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration
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

    /**
     * The number 0030 compares between two machines. It is the magnitude the
     * spread was taken at, which is why the two questions share one probe.
     */
    @Test
    fun `the same repeats also say what the probe took here, for another machine to be compared against`() {
        val probe = floor.probe.shouldNotBeNull()
        withClue("a probe is what a null step measured, so it is above zero and below the whole budget") {
            (probe.took > Duration.ZERO) shouldBe true
            (probe.took < BUDGET) shouldBe true
        }
    }

    @Test
    fun `a floor somebody declared has no probe behind it to compare a machine with`() {
        System.setProperty(RESOLUTION_PROPERTY, "0.02")
        try {
            declaredFloor()?.probe.shouldBeNull()
        } finally {
            System.clearProperty(RESOLUTION_PROPERTY)
        }
    }

    private companion object {

        /** Bounded well under the default, because this is one class of a build's worth of tests. */
        val BUDGET = 4.seconds

        // One calibration for the whole class. A second one is a second load on
        // a machine the rest of this module's tests are measuring themselves
        // against, and it moved a capacity search two rungs.
        val floor: Floor = calibrate(within = BUDGET)
    }
}
