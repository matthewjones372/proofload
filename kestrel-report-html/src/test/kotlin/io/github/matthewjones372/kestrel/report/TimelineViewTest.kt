package io.github.matthewjones372.kestrel.report

import io.github.matthewjones372.kestrel.Histogram
import io.github.matthewjones372.kestrel.RunRecorder
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class TimelineViewTest {

    /** The y of every point on one series, in the order they are drawn. */
    private fun heights(page: String, series: String): List<Double> {
        val points = Regex("""<polyline class="series $series" points="([^"]+)"""").find(page)
        return requireNotNull(points) { "no $series line on the page" }
            .groupValues[1]
            .split(" ")
            .map { it.substringAfter(",").toDouble() }
    }

    @Test
    fun `a run that degraded halfway looks different on the page from one that did not`() {
        val degraded = heights(Fixtures.degradedHalfway.toHtmlReport(), "p99")
        val steady = heights(Fixtures.steadyThroughout.toHtmlReport(), "p99")

        withClue("a target that held its latency is one height for the whole run: $steady") {
            steady.toSet().size shouldBe 1
        }
        withClue("a target that got slower halfway is not: $degraded") {
            (degraded.toSet().size > 1) shouldBe true
        }
    }

    @Test
    fun `throughput, latency and failures each get a chart`() {
        val page = Fixtures.degradedHalfway.toHtmlReport()

        page shouldContain """aria-label="requests a second""""
        page shouldContain """aria-label="service time each second""""
        page shouldContain """aria-label="failures a second""""
    }

    @Test
    fun `the width of the bucket a second's percentile came from is printed beside it`() {
        val page = Fixtures.degradedHalfway.toHtmlReport()

        page shouldContain Histogram.COARSE_PRECISION.asPercent()
        withClue("and it is wider than the one the table above prints") {
            (Histogram.COARSE_PRECISION > Histogram.PRECISION) shouldBe true
        }
    }

    @Test
    fun `a second nothing ran in is a point on the line rather than a gap in it`() {
        val recorder = RunRecorder(Instant.parse("2026-08-26T09:00:00Z"))
        repeat(30) { recorder.pay(at = 100.milliseconds) }
        repeat(10) { recorder.pay(at = 2_100.milliseconds) }

        val counts = heights(recorder.freeze().toHtmlReport(), "count")

        withClue("three seconds, each drawn as a segment: $counts") { counts.size shouldBe 6 }
        withClue("the quiet second sits on the floor: $counts") {
            counts[2] shouldBe counts.max()
            counts[3] shouldBe counts.max()
        }
    }

    @Test
    fun `each second is a flat segment, because nothing was measured between two of them`() {
        val counts = heights(Fixtures.degradedHalfway.toHtmlReport(), "count")

        withClue("points come in pairs at one height: $counts") {
            counts.chunked(2).all { (left, right) -> left == right } shouldBe true
        }
    }

    @Test
    fun `a run with nothing to plot is not given three empty charts`() {
        // The stylesheet is inlined into every page, so the claim is about the
        // markup rather than about the word.
        Fixtures.fellBehind.toHtmlReport() shouldNotContain """<section class="timeline""""
    }

    @Test
    fun `a run where nothing failed is not given a chart of zeroes`() {
        val page = Fixtures.steadyThroughout.toHtmlReport()

        page shouldContain """aria-label="requests a second""""
        page shouldNotContain """aria-label="failures a second""""
    }

    @Test
    fun `the page for a run over time matches its golden`() {
        Fixtures.degradedHalfway.toHtmlReport() shouldBe Golden.text("report-timeline.html")
    }

    private fun RunRecorder.pay(at: Duration) = record(
        step = "pay",
        failure = null,
        serviceTime = 20.milliseconds,
        schedulingDelay = Duration.ZERO,
        at = at,
    )
}
