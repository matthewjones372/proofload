package io.github.matthewjones372.proofload

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

/**
 * A percentile provokes one question — show me one — and an exemplar answers
 * it. One id per bucket, so a thousand samples keep tens of ids rather than a
 * thousand.
 */
class ExemplarTest {

    @Test
    fun `the id reported beside a percentile belongs to a request in that bucket`() {
        val histogram = Histogram()
        repeat(999) { histogram.record(10.milliseconds, trace = "fast") }
        histogram.record(900.milliseconds, trace = "the slow one")

        val timing = histogram.timing()

        withClue("p99.9 of a thousand samples is the slowest one") {
            timing.exemplar(99.9) shouldBe "the slow one"
        }
        timing.exemplar(50.0) shouldBe "fast"
    }

    @Test
    fun `a thousand samples keep one id per bucket, not one per request`() {
        val histogram = Histogram()
        // A real step's latencies cluster; these sit inside five milliseconds,
        // which is tens of buckets rather than a thousand.
        repeat(1_000) { sample ->
            histogram.record((20_000 + sample * 5).microseconds, trace = "trace-$sample")
        }

        val kept = histogram.timing().distribution.mapNotNull { it.trace }

        withClue("one per bucket that counted something: ${kept.size} of a thousand samples") {
            kept.size shouldBe histogram.timing().distribution.size
            (kept.size < 100) shouldBe true
        }
    }

    @Test
    fun `an untraced run reports no exemplar rather than an invented one`() {
        val histogram = Histogram()
        repeat(10) { histogram.record(10.milliseconds) }

        histogram.timing().exemplar(99.0).shouldBeNull()
    }

    @Test
    fun `a merge keeps the ids of both shards`() {
        val one = Histogram().apply { record(10.milliseconds, trace = "from one") }
        val other = Histogram().apply { record(900.milliseconds, trace = "from the other") }

        one.merge(other)

        one.timing().distribution.mapNotNull { it.trace } shouldContain "from the other"
        one.timing().distribution.mapNotNull { it.trace } shouldContain "from one"
    }
}
