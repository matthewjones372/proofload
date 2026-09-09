package io.github.matthewjones372.proofload

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class DistributionTest {

    private fun histogramOf(samples: List<Int>) =
        Histogram().apply { samples.forEach { record(it.milliseconds) } }

    @Test
    fun `a distribution counts every sample the histogram counted`() {
        val histogram = histogramOf((1..500).toList())

        histogram.timing().distribution.sumOf { it.count } shouldBe histogram.count
    }

    @Test
    fun `only buckets that were counted travel, so a report draws what was measured`() {
        val timing = histogramOf(listOf(10, 10, 10, 400)).timing()

        withClue("buckets: ${timing.distribution}") {
            timing.distribution.size shouldBe 2
            timing.distribution.first().count shouldBe 3L
            timing.distribution.last().count shouldBe 1L
        }
    }

    @Test
    fun `buckets arrive in order, so a chart does not have to sort them`() {
        val timing = histogramOf(listOf(400, 1, 90, 20)).timing()

        timing.distribution.map { it.upperBound } shouldBe timing.distribution.map { it.upperBound }.sorted()
    }

    @Test
    fun `a bucket's bound is where the histogram would report a sample in it`() {
        val timing = histogramOf(listOf(37)).timing()

        timing.distribution.single().upperBound shouldBe timing.p99
    }

    @Test
    fun `a timing over nothing has nothing to draw`() {
        Histogram().timing().distribution.shouldBeEmpty()
    }

    @Test
    fun `two humps stay two humps, which is the point of keeping the buckets`() {
        val timing = histogramOf(List(50) { 5 } + List(50) { 500 }).timing()

        timing.distribution.size shouldBe 2
        timing.distribution.map { it.count } shouldBe listOf(50L, 50L)
    }
}
