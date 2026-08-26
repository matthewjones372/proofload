package io.github.matthewjones372.kestrel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class PercentileTest {

    private val seeded = Random(20260826)

    private fun timingOf(samples: Int): Timing = Histogram()
        .apply { repeat(samples) { record(seeded.nextLong(80, 320).milliseconds) } }
        .timing()

    @Test
    fun `a percentile read off the frozen buckets is the one the histogram reported`() {
        val timing = timingOf(2_000)

        timing.percentile(50.0) shouldBe timing.p50
        timing.percentile(95.0) shouldBe timing.p95
        timing.percentile(99.0) shouldBe timing.p99
    }

    @Test
    fun `a frozen timing answers a percentile nobody asked for when it was frozen`() {
        val timing = timingOf(5_000)

        withClue("p99 ${timing.p99}, p99.95 ${timing.percentile(99.95)}, max ${timing.max}") {
            (timing.percentile(99.95) >= timing.p99) shouldBe true
            (timing.percentile(99.95) <= timing.max) shouldBe true
        }
    }

    @Test
    fun `a percentile of nothing is nothing, rather than a zero somebody reads as fast`() {
        Histogram().timing().percentile(99.9) shouldBe Duration.ZERO
    }

    @Test
    fun `a percentile outside nought to a hundred is a bug in the caller, not a number`() {
        shouldThrow<IllegalArgumentException> { timingOf(10).percentile(101.0) }
    }

    @Test
    fun `a tail is measured once there are samples enough to rest it on`() {
        val timing = timingOf(Timing.SAMPLES_FOR_P999.toInt())

        timing.p999 shouldBe Tail.Measured(timing.percentile(99.9))
    }

    @Test
    fun `a step too thin for a tail reports it absent, and says how thin`() {
        val tail = timingOf(999).p999

        tail.shouldBeInstanceOf<Tail.Absent>()
        tail.because shouldContain "999"
        tail.because shouldContain Timing.SAMPLES_FOR_P999.toString()
    }

    @Test
    fun `a tail sits above the percentile below it, which is what makes it worth printing`() {
        val timing = Histogram().apply {
            repeat(9_990) { record(seeded.nextLong(80, 120).milliseconds) }
            repeat(10) { record(seeded.nextLong(2_000, 3_000).milliseconds) }
        }.timing()

        withClue("p99 ${timing.p99}, p99.9 ${timing.p999}") {
            timing.p999 shouldBe Tail.Measured(timing.percentile(99.9))
            (timing.percentile(99.9) > timing.p99) shouldBe true
        }
    }
}
