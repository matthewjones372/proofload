package io.github.matthewjones372.proofload.report

import io.github.matthewjones372.proofload.Bucket
import io.github.matthewjones372.proofload.Timing
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * A distribution, drawn as the buckets that were counted.
 *
 * No interpolation and no smoothing: a bar is a bucket with something in it, at
 * the height of what it counted. What the chart shows is what the histogram
 * recorded, which is the difference between a graph that is evidence and one
 * that is decoration.
 *
 * The x axis is logarithmic because latency is. On a linear axis every fast
 * request in a normal run lands in the first pixel.
 */
internal fun Timing.distributionChart(label: String): List<String> {
    if (distribution.isEmpty()) return emptyList()

    val scale = LogScale(distribution)
    val tallest = distribution.maxOf { it.count }

    return listOf(
        """      <figure class="chart">""",
        """        <figcaption>${label.escapedForHtml()} — where the requests landed</figcaption>""",
        """        <svg viewBox="0 0 $WIDTH $HEIGHT" role="img" preserveAspectRatio="none"""" +
            """ aria-label="${label.escapedForHtml()} distribution">""",
    ) + distribution.flatMap { bucket -> bar(bucket, scale, tallest) } +
        marker(p50, scale, "p50") +
        marker(p99, scale, "p99") +
        axis(scale) +
        listOf("        </svg>", "      </figure>")
}

private fun bar(bucket: Bucket, scale: LogScale, tallest: Long): List<String> {
    val x = scale.x(bucket.upperBound)
    val height = PLOT * (bucket.count.toDouble() / tallest)
    return listOf(
        """          <rect class="bar" x="${x.round()}" y="${(PLOT - height).round()}"""" +
            """ width="$BAR_WIDTH" height="${height.round()}">""",
        """            <title>${bucket.count} at ${bucket.upperBound.forReport()}</title>""",
        "          </rect>",
    )
}

private fun marker(at: Duration, scale: LogScale, label: String): List<String> {
    val x = scale.x(at)
    return listOf(
        """          <line class="mark" x1="${x.round()}" y1="0" x2="${x.round()}" y2="$PLOT"></line>""",
        """          <text class="mark-label" x="${(x + LABEL_GAP).round()}" y="$LABEL_BASELINE">$label</text>""",
    )
}

/**
 * Ticks at decade boundaries — 1ms, 10ms, 100ms — rather than at bucket edges.
 * A bucket edge is a power of two, which means nothing to a reader.
 */
private fun axis(scale: LogScale): List<String> =
    scale.decades().flatMap { decade ->
        listOf(
            """          <line class="tick" x1="${scale.x(decade).round()}" y1="$PLOT"""" +
                """ x2="${scale.x(decade).round()}" y2="${PLOT + TICK_LENGTH}"></line>""",
            """          <text class="tick-label" x="${scale.x(decade).round()}" y="$HEIGHT">""" +
                "${decade.forReport()}</text>",
        )
    }

/** Nanoseconds to pixels, logarithmically, across the range that was measured. */
private class LogScale(buckets: List<Bucket>) {

    private val low = ln(buckets.first().upperBound.inWholeNanoseconds.coerceAtLeast(1L).toDouble())
    private val high = ln(buckets.last().upperBound.inWholeNanoseconds.coerceAtLeast(2L).toDouble())
    private val span = (high - low).takeIf { it > 0.0 } ?: 1.0

    fun x(at: Duration): Double {
        val value = ln(at.inWholeNanoseconds.coerceAtLeast(1L).toDouble())
        return ((value - low) / span * (WIDTH - BAR_WIDTH)).coerceIn(0.0, WIDTH - BAR_WIDTH)
    }

    /**
     * The powers of ten inside the measured range, as durations.
     *
     * The lower end reaches back one bucket: a run whose fastest sample is a
     * millisecond lands in a bucket that ends just above one, and dropping the
     * 1 ms tick for that would leave the chart's fast end unlabelled.
     */
    fun decades(): List<Duration> = (0..MAX_DECADE)
        .map { power -> TEN.pow(power).toLong().nanoseconds }
        .filter { ln(it.inWholeNanoseconds.toDouble()) in (low - LN_2)..high }
}

// Root locale, not the machine's: an SVG coordinate written as "12,5" in a
// locale that uses a decimal comma is a chart that renders as a smear.
private fun Double.round(): String = String.format(Locale.ROOT, "%.1f", this)

private const val WIDTH = 640.0
private const val PLOT = 120.0
private const val HEIGHT = 140
private const val BAR_WIDTH = 4.0
private const val TEN = 10.0
private const val LABEL_GAP = 3.0
private const val LABEL_BASELINE = 12
private const val TICK_LENGTH = 4
private const val MAX_DECADE = 13
private val LN_2 = ln(2.0)
