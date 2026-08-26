package io.github.matthewjones372.kestrel

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.time.Duration

/** The range a percentile could plausibly sit in, given how many samples there were. */
data class Interval(val low: Duration, val high: Duration) {

    infix fun overlaps(other: Interval): Boolean = low <= other.high && other.low <= high
}

/**
 * The 95% sampling interval for a percentile.
 *
 * The rank of a percentile in a sample is binomially distributed, so this
 * follows from the count and the buckets without assuming anything about the
 * shape of the latencies — no normality, no smoothing, no model. It is
 * arithmetic over what was measured.
 *
 * It is also the honest answer to "did this get worse": a p99 over 480 samples
 * rests on five of them, and its interval is wide enough that two runs will
 * often differ by tens of milliseconds having measured the same thing.
 */
fun Timing.interval(percentile: Double): Interval? {
    if (count == 0L || distribution.isEmpty()) return null

    val share = percentile / HUNDRED
    val rank = count * share
    val spread = CONFIDENCE * sqrt(count * share * (1 - share))

    val lowRank = floor(rank - spread).toLong().coerceIn(1L, count)
    val highRank = ceil(rank + spread).toLong().coerceIn(1L, count)

    return Interval(low = valueAtRank(lowRank), high = valueAtRank(highRank))
}

private const val HUNDRED = 100.0

/** 1.96 standard deviations: the 95% interval, wide enough not to invite acting on noise. */
private const val CONFIDENCE = 1.96
