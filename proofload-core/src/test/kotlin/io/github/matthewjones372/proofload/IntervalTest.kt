package io.github.matthewjones372.proofload

import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class IntervalTest {

    private val seeded = Random(20260826)

    private fun timingOf(samples: Int): Timing = Histogram()
        .apply { repeat(samples) { record(seeded.nextLong(80, 320).milliseconds) } }
        .timing()

    private fun Interval.width(): Duration = high - low

    @Test
    fun `a percentile of nothing has no interval to report`() {
        Histogram().timing().interval(99.0).shouldBeNull()
    }

    @Test
    fun `more samples narrow the interval, which is the only honest fix for a wide one`() {
        val few = timingOf(50).interval(99.0)!!
        val many = timingOf(5_000).interval(99.0)!!

        withClue("few ${few.width()}, many ${many.width()}") {
            (many.width() < few.width()) shouldBe true
        }
    }

    @Test
    fun `the percentile itself sits inside its own interval`() {
        val timing = timingOf(1_000)
        val interval = timing.interval(99.0)!!

        withClue("p99 ${timing.p99} in $interval") {
            (timing.p99 >= interval.low && timing.p99 <= interval.high) shouldBe true
        }
    }

    @Test
    fun `two runs of the same distribution overlap, so neither is worse`() {
        val before = timingOf(500).interval(99.0)!!
        val after = timingOf(500).interval(99.0)!!

        (before overlaps after) shouldBe true
    }

    @Test
    fun `a run three times slower does not overlap, so the change is real`() {
        val before = timingOf(500).interval(99.0)!!
        val after = Histogram()
            .apply { repeat(500) { record(seeded.nextLong(800, 1_200).milliseconds) } }
            .timing()
            .interval(99.0)!!

        (before overlaps after) shouldBe false
    }

    /**
     * On the shape a real service has: most requests fast, a few slow. The
     * tail is where the samples are thin, so it is where a percentile is least
     * pinned down — which is exactly the number people quote most confidently.
     */
    @Test
    fun `the tail of a skewed distribution is less certain than its middle`() {
        val timing = Histogram().apply {
            repeat(950) { record(seeded.nextLong(80, 120).milliseconds) }
            repeat(50) { record(seeded.nextLong(500, 3_000).milliseconds) }
        }.timing()

        withClue("p50 ${timing.interval(50.0)}, p99 ${timing.interval(99.0)}") {
            (timing.interval(50.0)!!.width() < timing.interval(99.0)!!.width()) shouldBe true
        }
    }
}
