package io.github.matthewjones372.proofload

import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * How virtual users actually arrived: [count] departures, [mean] between them,
 * and [cov] for how much that gap varied.
 *
 * Measured from the departures that went out, not read off the profile that
 * asked for them. A metronome is a [cov] of zero and a Poisson process is one
 * near 1.0, which is the check the standard advice asks a load test to make of
 * itself.
 */
data class Arrivals(val count: Long, val mean: Duration, val cov: Double) {

    companion object {
        /** For a result built from samples rather than run, which departed nobody. */
        val none: Arrivals = Arrivals(count = 0L, mean = Duration.ZERO, cov = 0.0)
    }
}

/**
 * The gaps between departures, folded as they go.
 *
 * Welford's, rather than a list of gaps to work over at the end: the count, the
 * mean and the sum of squares are three numbers whatever the run's length, and
 * the loop that books departures cannot afford an allocation per user. A
 * scheduling loop that pauses to grow a list departs late, and this tool
 * reports its own lateness as the target's response time.
 */
class ArrivalRecorder {

    // The mutable accumulator this class exists for: a builder that freezes
    // before it returns, on the one path whose delay this tool would otherwise
    // report as the target's latency.
    private var count = 0L
    private var previous = Duration.ZERO
    private var mean = 0.0
    private var sumOfSquares = 0.0

    /** [departure] is an offset from the start of the run, and they arrive in order. */
    fun record(departure: Duration) {
        if (count > 0) fold((departure - previous).inWholeNanoseconds.toDouble())
        previous = departure
        count += 1
    }

    fun freeze(): Arrivals {
        val gaps = count - 1
        val spacing = mean.toLong().nanoseconds
        if (gaps < 1 || mean <= 0.0) return Arrivals(count = count, mean = spacing, cov = 0.0)
        return Arrivals(count = count, mean = spacing, cov = sqrt(sumOfSquares / gaps) / mean)
    }

    // Welford's: the mean is corrected towards each gap as it arrives rather
    // than divided out at the end, so a ten-minute run costs the same as a
    // ten-second one and neither loses the small gaps to the large ones.
    private fun fold(gap: Double) {
        val gaps = count.toDouble()
        val before = gap - mean
        mean += before / gaps
        sumOfSquares += before * (gap - mean)
    }
}
