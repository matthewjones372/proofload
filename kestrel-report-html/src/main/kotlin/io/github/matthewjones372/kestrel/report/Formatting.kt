package io.github.matthewjones372.kestrel.report

import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * How a measurement is allowed to look on the page.
 *
 * The histogram reports the top of a bucket 0.78% wide, so a duration is shown
 * to three significant figures and no further. A fourth digit would be a digit
 * nobody measured, printed with the same confidence as the three that were.
 */
internal fun Duration.forReport(): String {
    val nanos = inWholeNanoseconds
    return when {
        nanos < NANOS_PER_MICRO -> "$nanos ns"
        nanos < NANOS_PER_MILLI -> significant(nanos / NANOS_PER_MICRO.toDouble()) + " µs"
        nanos < NANOS_PER_SECOND -> significant(nanos / NANOS_PER_MILLI.toDouble()) + " ms"
        else -> significant(nanos / NANOS_PER_SECOND.toDouble()) + " s"
    }
}

/** A fraction as a percentage, to the two figures the precision itself has. */
internal fun Double.asPercent(): String =
    String.format(Locale.ROOT, "%.2f%%", this * PERCENT)

/** Locale-independent on purpose: a report read in one country was written in another. */
internal fun Long.grouped(): String = String.format(Locale.ROOT, "%,d", this)

/** A rate, printed with the figures it was given and no more: `3,500/s`, `0.5/s`. */
internal fun Double.asRate(): String = "${String.format(Locale.ROOT, "%,.6g", this).trimNumber()}/s"

private fun String.trimNumber(): String = if (contains('.')) trimEnd('0').trimEnd('.') else this

private fun significant(value: Double): String {
    val decimals = when {
        value >= HUNDRED -> 0
        value >= TEN -> 1
        else -> 2
    }
    return String.format(Locale.ROOT, "%.${decimals}f", value)
}

private const val NANOS_PER_MICRO = 1_000L
private const val NANOS_PER_MILLI = 1_000_000L
private const val NANOS_PER_SECOND = 1_000_000_000L
private const val PERCENT = 100.0
private const val HUNDRED = 100.0
private const val TEN = 10.0

/**
 * A duration at the scale a load shape lives on.
 *
 * [forReport] is written for latency, where three significant figures matter;
 * a ten-minute hold rendered as "600 s" is technically the same number and
 * nobody reads it as ten minutes.
 */
internal fun Duration.forPlan(): String = when {
    this >= 1.hours -> "%.1f h".format(Locale.ROOT, inWholeSeconds / SECONDS_PER_HOUR)
    this >= 1.minutes -> "%.1f min".format(Locale.ROOT, inWholeSeconds / SECONDS_PER_MINUTE)
    else -> forReport()
}

private const val SECONDS_PER_MINUTE = 60.0
private const val SECONDS_PER_HOUR = 3600.0
