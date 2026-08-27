package io.github.matthewjones372.kestrel.report

import io.github.matthewjones372.kestrel.Histogram
import io.github.matthewjones372.kestrel.RunResult
import java.util.Locale
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The run second by second: what left, how long the target took, and what
 * failed.
 *
 * A whole-run p99 cannot tell a target that degraded after ninety seconds from
 * one that was evenly slow, and every other number on the page is a whole-run
 * number.
 *
 * Step lines rather than sloped ones: nothing was measured between two
 * seconds, so a line sliding from one to the next would be drawing a reading
 * nobody took.
 */
internal fun RunResult.timelineLines(): List<String> {
    if (timeline.size < LEAST_SECONDS) return emptyList()

    return listOf(
        """  <section class="timeline" aria-label="Over time">""",
        "    <h2>Over time</h2>",
    ) + throughputChart() + latencyChart() + failureChart() + noteLines() + listOf("  </section>")
}

private fun RunResult.throughputChart(): List<String> {
    val counts = timeline.map { it.count.toDouble() }
    return chart(
        caption = "requests a second — peak ${counts.max().toLong().grouped()}",
        label = "requests a second",
        series = listOf("count" to counts),
        over = timeline.size,
    )
}

private fun RunResult.latencyChart(): List<String> {
    val p50 = timeline.map { it.p50.inWholeNanoseconds.toDouble() }
    val p99 = timeline.map { it.p99.inWholeNanoseconds.toDouble() }
    return chart(
        caption = """service time — <span class="key p99">p99</span> peaks at ${p99.max().asDuration()}, """ +
            """<span class="key p50">p50</span> at ${p50.max().asDuration()}""",
        label = "service time each second",
        series = listOf("p50" to p50, "p99" to p99),
        over = timeline.size,
    )
}

/** Absent when nothing failed: a line pinned to zero for the whole run is a chart that says what the totals said. */
private fun RunResult.failureChart(): List<String> {
    val failed = timeline.map { it.failed.toDouble() }
    return chart(
        caption = "failures a second — peak ${failed.max().toLong().grouped()}",
        label = "failures a second",
        series = listOf("failed" to failed),
        over = timeline.size,
    )
}

private fun chart(caption: String, label: String, series: List<Pair<String, List<Double>>>, over: Int): List<String> {
    val peak = series.maxOf { (_, values) -> values.max() }.takeIf { it > 0.0 } ?: return emptyList()

    return listOf(
        """    <figure class="chart over-time">""",
        """      <figcaption>$caption</figcaption>""",
        """      <svg viewBox="0 0 $WIDTH $HEIGHT" role="img" preserveAspectRatio="none" aria-label="$label">""",
    ) + series.map { (name, values) ->
        """        <polyline class="series $name" points="${stepped(values, peak)}"></polyline>"""
    } + listOf(
        """        <text class="tick-label series-start" x="0" y="$HEIGHT">0</text>""",
        """        <text class="tick-label series-end" x="$WIDTH" y="$HEIGHT">${over.seconds.forPlan()}</text>""",
        "      </svg>",
        "    </figure>",
    )
}

/** Each second held flat across its own width, so a second is a segment rather than a corner. */
private fun stepped(values: List<Double>, peak: Double): String =
    values.withIndex().joinToString(separator = " ") { (index, value) ->
        // A little headroom, so a run at a flat rate is a band rather than a
        // line pinned to the top edge of its own box.
        val y = HEADROOM + (1 - value / peak) * (PLOT - HEADROOM)
        val from = WIDTH * index / values.size
        val to = WIDTH * (index + 1) / values.size
        "${from.round()},${y.round()} ${to.round()},${y.round()}"
    }

private fun noteLines(): List<String> = listOf(
    """    <p class="note">One point a second, counted from the run's start, and a second nothing ran in is """ +
        "drawn as the zero it was. These percentiles come from a coarse histogram — good to " +
        "${Histogram.COARSE_PRECISION.asPercent()}, against ${Histogram.PRECISION.asPercent()} for the table " +
        "above — because a full one a second per step is tens of megabytes of counters. Read a shape here and " +
        "a number there.</p>",
)

private fun Double.asDuration(): String = toLong().nanoseconds.forReport()

private fun Double.round(): String = String.format(Locale.ROOT, "%.1f", this)

/** Under this there is no shape to draw, only the summary again. */
private const val LEAST_SECONDS = 2

private const val WIDTH = 640.0
private const val PLOT = 60.0
private const val HEADROOM = 8.0
private const val HEIGHT = 78
