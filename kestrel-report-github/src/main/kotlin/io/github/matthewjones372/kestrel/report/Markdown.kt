package io.github.matthewjones372.kestrel.report

import io.github.matthewjones372.kestrel.Floor
import io.github.matthewjones372.kestrel.Histogram
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.StepStats
import io.github.matthewjones372.kestrel.fellBehind
import io.github.matthewjones372.kestrel.seeds
import io.github.matthewjones372.kestrel.unanswered
import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * What a run measured, as a table GitHub renders and a terminal still reads:
 * no colour, no emoji, and the columns padded so the numbers line up wherever
 * it lands — a job summary, a PR body or a job log.
 */
fun RunResult.markdown(floor: Floor? = null): String =
    blocks(floor).joinToString(separator = "\n\n", postfix = "\n")

private fun RunResult.blocks(floor: Floor?): List<String> =
    if (steps.isEmpty()) {
        listOf("No steps ran.", "Started $startedAt.")
    } else {
        listOfNotNull(lostWarning(), floor?.line(), behindWarning()) + stepTable() +
            listOfNotNull(hiccupLine()) + failureBlocks() + totals() +
            listOfNotNull(arrivalLine()) + MEASUREMENT_NOTE
    }

/**
 * Above the backlog warning and above the table, because a record that never
 * arrived is not a missing sample: every throughput number under it is counting
 * work the target may never have finished.
 */
private fun RunResult.lostWarning(): String? {
    if (unanswered.isEmpty()) return null
    val where = unanswered.joinToString(separator = "; ") { step ->
        "${step.name.escapeMarkdown()} — ${step.unmatched} unmatched, ${step.inFlight} in flight"
    }
    return "> **Records that never arrived:** $where. " +
        "An unmatched record is one the sink had the whole drain window to answer for and did not; " +
        "an in-flight one left too late to be given that window."
}

/**
 * Above the table, because it is the frame for every number under it, and below
 * the lost records, because a record that never arrived outranks a caveat about
 * precision. A machine too coarse to bound a claim gets the warning form: this
 * report has no comparison to withhold, so saying it plainly is all it can do.
 */
private fun Floor.line(): String =
    if (supportsAClaim) {
        "Calibrated on this machine: differences under ${resolution.asPercent()} are not resolvable here. " +
            "The injector's own stalls reached ${hiccups.p99.report()} at p99."
    } else {
        "> **This machine cannot support a latency claim.** Repeats of one unchanging measurement landed " +
            "${resolution.asPercent()} apart here, so nothing smaller than that is the code rather than " +
            "the machine."
    }

private fun Double.asPercent(): String = String.format(Locale.ROOT, "%.2f%%", this * PERCENT)

/**
 * Under the table rather than over it, because it is what the tail above is
 * measured against. Absent when nothing watched: a result assembled from
 * samples has no injector to have stalled.
 */
private fun RunResult.hiccupLine(): String? {
    if (hiccups.count == 0L) return null
    return "The injector's own JVM stalled for ${hiccups.p99.report()} at p99 and ${hiccups.max.report()} at " +
        "worst, measured on a thread no request ran on. A tail that size is this machine as readily as the target."
}

/**
 * A line rather than a warning. Even arrivals are not wrong, they are a choice
 * whose consequence — a p99 that is optimistic against the same mean rate in
 * production — is invisible unless the report names which was asked for.
 */
private fun RunResult.arrivalLine(): String? {
    val shape = plan.profile ?: return null
    val drawn = shape.seeds
    val asked =
        if (drawn.isEmpty()) "Arrivals were evenly spaced, which understates queueing against the same mean rate " +
            "in production."
        else "Arrivals were drawn from ${if (drawn.size == 1) "seed" else "seeds"} ${drawn.joinToString(", ")}."

    if (arrivals.count < 2L) return asked
    return "$asked Measured ${arrivals.mean.report()} between departures, coefficient of variation " +
        "${String.format(Locale.ROOT, "%.2f", arrivals.cov)}."
}

private fun RunResult.behindWarning(): String? {
    if (!fellBehind()) return null
    return "> **Behind schedule:** ${behind.p99.report()} late at p99, ${behind.max.report()} at worst. " +
        "The response times below include that backlog."
}

private fun RunResult.stepTable(): String = table(
    columns = listOf(
        Column("Step", Align.LEFT),
        Column("Requests", Align.RIGHT),
        Column("OK", Align.RIGHT),
        Column("Failed", Align.RIGHT),
        Column("p50", Align.RIGHT),
        Column("p95", Align.RIGHT),
        Column("p99", Align.RIGHT),
        Column("Max", Align.RIGHT),
    ),
    rows = steps.values.map { it.row() },
)

private fun StepStats.row(): List<String> = listOf(
    name.escapeMarkdown(),
    count.toString(),
    ok.count.toString(),
    failed.count.toString(),
    responseTime.p50.report(),
    responseTime.p95.report(),
    responseTime.p99.report(),
    responseTime.max.report(),
)

/**
 * Three significant digits wherever the value sits. `Duration.toString()` prints
 * every nanosecond it holds, and a percentile is the top of a bucket good to
 * [Histogram.PRECISION] — so a fourth digit is the report claiming a precision
 * nobody measured.
 */
private fun Duration.report(): String {
    if (this == Duration.ZERO) return "0s"
    val unit = unitOf(inWholeNanoseconds)
    val magnitude = floor(log10(toDouble(unit))).toInt()
    return toString(unit, (SIGNIFICANT_DIGITS - 1 - magnitude).coerceIn(0, SIGNIFICANT_DIGITS))
}

private fun unitOf(nanos: Long): DurationUnit = when {
    nanos < NANOS_PER_MICRO -> DurationUnit.NANOSECONDS
    nanos < NANOS_PER_MILLI -> DurationUnit.MICROSECONDS
    nanos < NANOS_PER_SECOND -> DurationUnit.MILLISECONDS
    else -> DurationUnit.SECONDS
}

private fun RunResult.failureBlocks(): List<String> {
    val rows = steps.values.flatMap { step ->
        step.failed.reasons.map { (reason, seen) ->
            listOf(step.name.escapeMarkdown(), reason.escapeMarkdown(), seen.toString())
        }
    }
    if (rows.isEmpty()) return emptyList()

    val columns = listOf(Column("Step", Align.LEFT), Column("Failure", Align.LEFT), Column("Count", Align.RIGHT))
    return listOf("**Failures**", table(columns, rows))
}

private fun RunResult.totals(): String = "$count requests, $ok ok, $failed failed. Started $startedAt."

private enum class Align { LEFT, RIGHT }

private class Column(val header: String, val align: Align)

private fun table(columns: List<Column>, rows: List<List<String>>): String {
    val widths = columns.mapIndexed { index, column ->
        maxOf(MIN_COLUMN_WIDTH, column.header.length, rows.maxOfOrNull { it[index].length } ?: 0)
    }
    val rule = columns.mapIndexed { index, column ->
        val dashes = "-".repeat(widths[index] - 1)
        if (column.align == Align.LEFT) ":$dashes" else "$dashes:"
    }
    val header = line(columns.map { it.header }, columns, widths)
    return (listOf(header, rule.wrapped()) + rows.map { line(it, columns, widths) }).joinToString("\n")
}

private fun line(cells: List<String>, columns: List<Column>, widths: List<Int>): String =
    cells.mapIndexed { index, cell ->
        if (columns[index].align == Align.LEFT) cell.padEnd(widths[index]) else cell.padStart(widths[index])
    }.wrapped()

private fun List<String>.wrapped(): String = joinToString(separator = " | ", prefix = "| ", postfix = " |")

/**
 * A failure reason is whatever the target said, so it arrives carrying the
 * characters that end a table cell or open a tag. Backslash-escaping ASCII
 * punctuation is what CommonMark defines for this, and it leaves the text
 * readable in a terminal, where nothing renders and the backslashes are the
 * only cost.
 */
private fun String.escapeMarkdown(): String = map { character ->
    when {
        character in ACTIVE_CHARACTERS -> "\\$character"

        // A newline inside a cell ends the row; a control character is not text.
        character.isISOControl() -> " "

        else -> character.toString()
    }
}.joinToString(separator = "")

private const val ACTIVE_CHARACTERS = "\\`*_[]<>|"

// Five, so the alignment colon in the rule still has four dashes to sit
// against and the separator reads as a separator in a terminal.
private const val MIN_COLUMN_WIDTH = 5

private const val SIGNIFICANT_DIGITS = 3
private const val NANOS_PER_MICRO = 1_000L
private const val NANOS_PER_MILLI = 1_000_000L
private const val NANOS_PER_SECOND = 1_000_000_000L

private const val PERCENT = 100.0

private val PRECISION_PERCENT: String = String.format(Locale.ROOT, "%.2f", Histogram.PRECISION * PERCENT)

private val MEASUREMENT_NOTE: String =
    "Latency is response time, measured from the departure the profile promised. " +
        "Each percentile is the top of its histogram bucket, so it is within " +
        "$PRECISION_PERCENT% and never interpolated."
