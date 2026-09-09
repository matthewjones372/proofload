package io.github.matthewjones372.proofload

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.nanoseconds

/**
 * The histogram against the samples themselves.
 *
 * Every other test here compares one of its answers to another of its answers,
 * which cannot catch an error both share. This sorts the raw values and asks
 * whether the bucketed answer is inside the precision it advertises.
 */
class HistogramAccuracyTest {

    private val seeded = Random(20260826)

    private fun exactPercentile(samples: List<Duration>, percentile: Double): Duration {
        val sorted = samples.sorted()
        val rank = Math.ceil(percentile / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    private fun check(samples: List<Duration>, percentile: Double) {
        val histogram = Histogram()
        samples.forEach { histogram.record(it) }

        val exact = exactPercentile(samples, percentile)
        val reported = histogram.percentile(percentile)
        val slack = exact * Histogram.PRECISION

        withClue("p$percentile: reported $reported, exact $exact, slack $slack") {
            (reported >= exact) shouldBe true
            (reported <= exact + slack) shouldBe true
        }
    }

    @Test
    fun `a percentile is within the precision it advertises, and never under the truth`() {
        val samples = List(10_000) { seeded.nextLong(1, 2_000_000_000L).nanoseconds }

        listOf(50.0, 90.0, 99.0, 99.9, 100.0).forEach { check(samples, it) }
    }

    @Test
    fun `a distribution with a long tail is not flattened by the buckets`() {
        val fast = List(9_900) { seeded.nextLong(500, 2_000).microseconds }
        val slow = List(100) { seeded.nextLong(2_000_000, 3_000_000).microseconds }

        listOf(50.0, 99.0, 99.9).forEach { check(fast + slow, it) }
    }

    @Test
    fun `every recorded sample is counted exactly once`() {
        val histogram = Histogram()
        val samples = List(5_000) { seeded.nextLong(1, 1_000_000_000L).nanoseconds }
        samples.forEach { histogram.record(it) }

        histogram.count shouldBe samples.size.toLong()
    }

    @Test
    fun `merging keeps every sample, so sharded recorders do not lose requests`() {
        val shards = List(8) { Histogram() }
        val samples = List(4_000) { seeded.nextLong(1, 100_000_000L).nanoseconds }
        samples.forEachIndexed { index, sample -> shards[index % shards.size].record(sample) }

        val merged = Histogram()
        shards.forEach(merged::merge)

        merged.count shouldBe samples.size.toLong()
        check(samples, 99.0)
    }
}
