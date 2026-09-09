package io.github.matthewjones372.proofload

import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ShareTest {

    private fun timingOf(samples: List<Duration>): Timing =
        Histogram().apply { samples.forEach { record(it) } }.timing()

    @Test
    fun `a timing whose samples all sit below the target met it entirely`() {
        val timing = timingOf(listOf(10.milliseconds, 20.milliseconds, 30.milliseconds))

        timing.share(under = 1.seconds) shouldBe Met.Measured(1.0)
    }

    @Test
    fun `a share is the samples that met the target over every sample taken`() {
        val timing = timingOf(List(3) { 10.milliseconds } + List(1) { 900.milliseconds })

        timing.share(under = 1.seconds) shouldBe Met.Measured(1.0)
        timing.share(under = 100.milliseconds) shouldBe Met.Measured(0.75)
    }

    @Test
    fun `a target inside a bucket counts that whole bucket as missing`() {
        val timing = timingOf(listOf(100.milliseconds))
        val reportedAt = timing.distribution.single().upperBound

        withClue("a 100 ms sample is reported at $reportedAt, which is above the target") {
            (reportedAt > 100.milliseconds) shouldBe true
            timing.share(under = 100.milliseconds) shouldBe Met.Measured(0.0)
        }
    }

    @Test
    fun `a target on a bucket's top is met by it, which is where its samples are reported`() {
        val timing = timingOf(listOf(100.milliseconds))

        timing.share(under = timing.distribution.single().upperBound) shouldBe Met.Measured(1.0)
    }

    @Test
    fun `a target below everything measured is met by nothing`() {
        timingOf(listOf(10.milliseconds, 20.milliseconds)).share(under = 1.milliseconds) shouldBe Met.Measured(0.0)
    }

    @Test
    fun `a timing over nothing reports the share absent rather than zero`() {
        val share = Histogram().timing().share(under = 1.seconds)

        share.shouldBeInstanceOf<Met.Absent>()
        share.because shouldContain "nothing"
    }

    @Test
    fun `the share at a percentile is at least that percentile, so the two cannot disagree`() {
        val timing = timingOf(List(95) { 10.milliseconds } + List(5) { 800.milliseconds })

        val met = timing.share(under = timing.p95).shouldBeInstanceOf<Met.Measured>()
        withClue("p95 ${timing.p95}, share ${met.fraction}") {
            (met.fraction >= 0.95) shouldBe true
        }
    }

    @Test
    fun `a share counts every bucket below the target, not only the one it lands in`() {
        val timing = timingOf(List(2) { 10.milliseconds } + List(2) { 50.milliseconds } + List(4) { 5.seconds })

        val met = timing.share(under = 1.seconds).shouldBeInstanceOf<Met.Measured>()
        met.fraction shouldBe (0.5 plusOrMinus 1e-9)
    }
}
