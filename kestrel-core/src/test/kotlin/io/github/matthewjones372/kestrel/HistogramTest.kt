package io.github.matthewjones372.kestrel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.Matcher
import io.kotest.matchers.MatcherResult
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

class HistogramTest {

    @Test
    fun `an empty histogram counts nothing and reports nothing`() {
        val histogram = Histogram()

        histogram.count shouldBe 0L
        histogram.percentile(99.0) shouldBe Duration.ZERO
    }

    @Test
    fun `a recorded value comes back within the stated precision`() {
        val histogram = Histogram()
        histogram.record(37.milliseconds)

        histogram.percentile(50.0) shouldBe within(37.milliseconds, Histogram.PRECISION)
    }

    @Test
    fun `precision holds across four decades, so a fast step is measured as well as a slow one`() {
        listOf(53.microseconds, 4.milliseconds, 610.milliseconds, 7.seconds).forEach { value ->
            val histogram = Histogram()
            histogram.record(value)

            withClue("$value") { histogram.percentile(99.0) shouldBe within(value, Histogram.PRECISION) }
        }
    }

    @Test
    fun `a percentile is the top of the bucket it landed in, so it never flatters the target`() {
        val histogram = Histogram()
        histogram.record(100.milliseconds)

        withClue("reported ${histogram.percentile(99.0)}") {
            (histogram.percentile(99.0) >= 100.milliseconds) shouldBe true
        }
    }

    @Test
    fun `percentiles walk the distribution rather than the order things arrived`() {
        val histogram = Histogram()
        (1..100).shuffled().forEach { histogram.record(it.milliseconds) }

        histogram.count shouldBe 100L
        histogram.percentile(50.0) shouldBe within(50.milliseconds, Histogram.PRECISION)
        histogram.percentile(99.0) shouldBe within(99.milliseconds, Histogram.PRECISION)
        histogram.max shouldBe within(100.milliseconds, Histogram.PRECISION)
    }

    @Test
    fun `merging two histograms says what one histogram given both would say`() {
        val left = Histogram()
        val right = Histogram()
        val both = Histogram()
        (1..50).forEach { left.record(it.milliseconds); both.record(it.milliseconds) }
        (51..100).forEach { right.record(it.milliseconds); both.record(it.milliseconds) }

        left.merge(right)

        left.count shouldBe both.count
        left.percentile(99.0) shouldBe both.percentile(99.0)
        left.max shouldBe both.max
    }

    @Test
    fun `a value past the ceiling is counted at the ceiling and admitted to`() {
        val histogram = Histogram()
        histogram.record(2.hours)

        histogram.count shouldBe 1L
        histogram.overflowed shouldBe 1L
        (histogram.max >= 1.hours) shouldBe true
    }

    @Test
    fun `a negative duration is a bug in the caller, not a slow response`() {
        shouldThrow<IllegalArgumentException> { Histogram().record((-1).nanoseconds) }
    }

    private fun within(expected: Duration, precision: Double): Matcher<Duration> =
        Matcher { actual ->
            val slack = expected * precision
            MatcherResult(
                actual >= expected - slack && actual <= expected + slack,
                { "$actual is not within ${precision * 100}% of $expected" },
                { "$actual is within ${precision * 100}% of $expected" },
            )
        }
}
