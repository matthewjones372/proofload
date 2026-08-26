package io.github.matthewjones372.kestrel.report

import io.github.matthewjones372.kestrel.Histogram
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.StepStats
import io.github.matthewjones372.kestrel.fellBehind
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
fun RunResult.markdown(): String = blocks().joinToString(separator = "\n\n", postfix = "\n")

private fun RunResult.blocks(): List<String> =
    if (steps.isEmpty()) {
        listOf("No steps ran.", "Started $startedAt.")
    } else {
        listOfNotNull(behindWarning()) + stepTable() + failureBlocks() + totals() + MEASUREMENT_NOTE
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
    ok.toString(),
    failed.toString(),
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
        step.failures.map { (reason, seen) ->
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
