package io.github.matthewjones372.kestrel

import kotlin.random.Random

/**
 * The range one point's reading could plausibly sit in, given how far the runs
 * behind it landed apart — in the units the statistic is read in.
 *
 * Three range types now, and they are not interchangeable. [Spread] is where a
 * *ratio* between two points sits; [Interval] is the same question inside one
 * run, over the samples it took; this is one point's own reading, resampled
 * from its runs. A trend draws these and compares with [Spread]s: a ratio
 * interval cannot be recovered from two bands, and two bands failing to overlap
 * is a stricter test than the one 0038 makes.
 */
data class Band(val low: Double, val high: Double) {

    operator fun contains(reading: Double): Boolean = reading in low..high
}

/**
 * What these runs can claim about [statistic], or nothing where they cannot
 * claim anything.
 *
 * The same resampling `against` uses, on one side and with no division: the
 * same seed and the same ten thousand, so a band drawn beside a comparison was
 * made the same way as the comparison.
 *
 * Absent under five runs, for the reason a comparison is refused under five —
 * a bootstrap over three values is arithmetic wearing a lab coat — and absent
 * where a run here never measured the statistic, rather than reading zero.
 */
fun Runs.band(statistic: Statistic): Band? {
    if (size < ENOUGH) return null
    val samples = each.map { statistic.samplesIn(it) ?: return null }
    if (samples.any { statistic.read(it) == null }) return null

    val random = Random(SEED)
    val resampler = Resampler(samples)
    val readings = DoubleArray(RESAMPLES) { statistic.read(resampler.resample(random)) ?: 0.0 }
    readings.sort()

    return Band(low = readings.quantile(LOW), high = readings.quantile(HIGH))
}
