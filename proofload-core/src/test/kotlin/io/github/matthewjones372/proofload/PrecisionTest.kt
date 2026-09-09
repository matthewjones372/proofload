package io.github.matthewjones372.proofload

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The histogram refuses to merge across precisions; the frozen value everything
 * downstream reads did not know its own, so the guarantee stopped at the
 * freeze.
 */
class PrecisionTest {

    private fun fullOf(vararg millis: Int) =
        Histogram().apply { millis.forEach { record(it.milliseconds) } }.timing()

    private fun coarseOf(vararg millis: Int) =
        Histogram.coarse().apply { millis.forEach { record(it.milliseconds) } }.timing()

    @Test
    fun `a timing frozen from a coarse histogram reports the coarse figure`() {
        coarseOf(10, 20, 30).precision shouldBe Histogram.COARSE_PRECISION
        fullOf(10, 20, 30).precision shouldBe Histogram.PRECISION
    }

    @Test
    fun `nothing counted is no bucket to be a width of`() {
        withClue("a timing that claimed a precision it never measured is the lie this exists to stop") {
            Timing.none.precision.shouldBeNull()
        }
    }

    @Test
    fun `merging unlike precisions is refused, and the refusal names both`() {
        val why = shouldThrow<IllegalArgumentException> {
            listOf(fullOf(10, 20), coarseOf(10, 20)).merged()
        }.message.orEmpty()

        withClue(why) {
            why shouldContain "${Histogram.PRECISION}"
            why shouldContain "${Histogram.COARSE_PRECISION}"
        }
    }

    @Test
    fun `merging like precisions keeps the one they agreed on`() {
        listOf(coarseOf(10, 20), coarseOf(30, 40)).merged().precision shouldBe Histogram.COARSE_PRECISION
    }

    @Test
    fun `an empty timing merges with anything, having claimed nothing`() {
        withClue("Timing.none is the neutral element half this codebase folds over") {
            listOf(Timing.none, coarseOf(10, 20)).merged().precision shouldBe Histogram.COARSE_PRECISION
            listOf(Timing.none, Timing.none).merged().precision.shouldBeNull()
        }
    }

    @Test
    fun `an empty table still knows how wide it would have been`() {
        withClue("the width is a property of the counters, not of what landed in them") {
            Histogram.coarse().timing().precision shouldBe Histogram.COARSE_PRECISION
            Histogram().timing().precision shouldBe Histogram.PRECISION
        }
        withClue("and a merge of empty seconds must not quietly drop it") {
            listOf(Histogram.coarse().timing(), Histogram.coarse().timing()).merged().precision shouldBe
                Histogram.COARSE_PRECISION
        }
    }

    @Test
    fun `a run's steps and its timeline are different widths, and each says which`() {
        val recorder = RunRecorder(Instant.parse("2026-08-26T09:00:00Z"))
        repeat(200) { recorder.record("pay", null, 20.milliseconds, Duration.ZERO, at = Duration.ZERO) }
        val result = recorder.freeze()

        result["pay"].serviceTime.precision shouldBe Histogram.PRECISION
        withClue("the timeline is an eighth of the counters, and eight times the bucket") {
            result.timeline.first().okServiceTime.precision shouldBe Histogram.COARSE_PRECISION
        }
    }
}
