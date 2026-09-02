package io.github.matthewjones372.kestrel

import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Little's law needs a mean, and the mean has to come off the same buckets
 * every other number here does — a second running total is a number that can
 * disagree with them.
 */
class MeanTest {

    private fun timingOf(samples: List<Duration>): Timing =
        Histogram().apply { samples.forEach { record(it) } }.timing()

    @Test
    fun `the mean of known samples is at or above their true mean, and within the precision`() {
        val samples = listOf(10.milliseconds, 20.milliseconds, 30.milliseconds, 40.milliseconds)
        val truth = 25.milliseconds

        val mean = timingOf(samples).mean

        withClue("every sample counts at the top of its bucket, so this rounds up: $mean") {
            mean shouldBeGreaterThanOrEqualTo truth
            mean shouldBeLessThanOrEqualTo truth * (1.0 + Histogram.PRECISION)
        }
    }

    @Test
    fun `a merged timing reports the merge's mean, not the average of two means`() {
        val one = Histogram().apply { repeat(999) { record(10.milliseconds) } }
        val other = Histogram().apply { record(1_000.milliseconds) }

        one.merge(other)

        val mean = one.timing().mean
        withClue("a thousand samples, one of them huge: the mean is near ten, not near five hundred") {
            mean shouldBeLessThanOrEqualTo 12.milliseconds
            mean shouldBeGreaterThanOrEqualTo 10.milliseconds
        }
    }

    @Test
    fun `nothing recorded is a mean of zero, which the count beside it explains`() {
        Timing.none.mean shouldBe Duration.ZERO
    }
}
