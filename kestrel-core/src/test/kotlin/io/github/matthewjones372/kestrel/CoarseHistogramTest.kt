package io.github.matthewjones372.kestrel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

/**
 * The cheap histogram, against the samples themselves and against the full one
 * it has to sit beside without changing.
 */
class CoarseHistogramTest {

    private val seeded = Random(20260827)

    private fun exactPercentile(samples: List<Duration>, percentile: Double): Duration {
        val sorted = samples.sorted()
        val rank = Math.ceil(percentile / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    @Test
    fun `a coarse percentile is within the precision it states, and never under the truth`() {
        val samples = List(10_000) { seeded.nextLong(1, 2_000_000_000L).nanoseconds }
        val histogram = Histogram.coarse()
        samples.forEach { histogram.record(it) }

        listOf(50.0, 90.0, 99.0, 100.0).forEach { percentile ->
            val exact = exactPercentile(samples, percentile)
            val reported = histogram.percentile(percentile)

            withClue("p$percentile: reported $reported, exact $exact") {
                (reported >= exact) shouldBe true
                (reported <= exact + exact * histogram.precision) shouldBe true
            }
        }
    }

    @Test
    fun `a coarse histogram says how coarse it is, and it is coarser than the full one`() {
        Histogram.coarse().precision shouldBe Histogram.COARSE_PRECISION
        Histogram().precision shouldBe Histogram.PRECISION

        (Histogram.COARSE_PRECISION > Histogram.PRECISION) shouldBe true
    }

    @Test
    fun `the full histogram's counters are the size they were, so the summary is untouched`() {
        Histogram().counterBytes shouldBe 43_016L
    }

    @Test
    fun `a coarse histogram costs a fraction of a full one, which is what pays for one a second`() {
        val coarse = Histogram.coarse().counterBytes
        val run = A_TEN_MINUTE_THREE_STEP_RUN * coarse

        coarse shouldBe 5_384L
        withClue("$run bytes for the run, against ${A_TEN_MINUTE_THREE_STEP_RUN * Histogram().counterBytes}") {
            (run < 10_000_000L) shouldBe true
            (run * 5 < A_TEN_MINUTE_THREE_STEP_RUN * Histogram().counterBytes) shouldBe true
        }
    }

    @Test
    fun `two coarse histograms merged say what one given both would say`() {
        val left = Histogram.coarse()
        val right = Histogram.coarse()
        val both = Histogram.coarse()
        (1..50).forEach { left.record(it.milliseconds); both.record(it.milliseconds) }
        (51..100).forEach { right.record(it.milliseconds); both.record(it.milliseconds) }

        left.merge(right)

        left.count shouldBe both.count
        left.percentile(99.0) shouldBe both.percentile(99.0)
        left.max shouldBe both.max
    }

    @Test
    fun `merging a coarse histogram into a full one is refused rather than answered wrongly`() {
        shouldThrow<IllegalArgumentException> { Histogram().merge(Histogram.coarse()) }
        shouldThrow<IllegalArgumentException> { Histogram.coarse().merge(Histogram()) }
    }

    @Test
    fun `a coarse table reaches the same ceiling, so a hung connection lands in it and not past it`() {
        val histogram = Histogram.coarse()
        histogram.record(Histogram.ceiling)
        histogram.record(2.hours)

        histogram.count shouldBe 2L
        histogram.overflowed shouldBe 1L
        (histogram.max >= 1.hours) shouldBe true
    }

    @Test
    fun `an empty coarse histogram counts nothing and reports nothing`() {
        val histogram = Histogram.coarse()

        histogram.count shouldBe 0L
        histogram.percentile(99.0) shouldBe Duration.ZERO
    }

    private companion object {
        /** One a second per step, which is the whole argument for a second precision. */
        const val A_TEN_MINUTE_THREE_STEP_RUN = 1_800L
    }
}
