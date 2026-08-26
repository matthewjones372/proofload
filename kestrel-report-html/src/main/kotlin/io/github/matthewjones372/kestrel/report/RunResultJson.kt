package io.github.matthewjones372.kestrel.report

import io.github.matthewjones372.kestrel.Arrivals
import io.github.matthewjones372.kestrel.Histogram
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.StepStats
import io.github.matthewjones372.kestrel.Tail
import io.github.matthewjones372.kestrel.Timing

/**
 * A `RunResult` as JSON, so the page carries a machine-readable copy of the run
 * it is showing and a reader can pull a number out of a CI artifact without
 * scraping the table.
 *
 * Durations are the nanoseconds the histogram reported, not a millisecond
 * someone rounded on the way past: the encoding holds the measurement and the
 * page does the formatting, so there is exactly one place a digit can be lost.
 *
 * Written by hand because a JSON library on this module's classpath would be
 * the first half of "opens with nothing fetched" given away.
 */
internal fun RunResult.toJson(): String = jsonObject(
    depth = 0,
    fields = listOf(
        "startedAt" to jsonString(startedAt.toString()),
        "durationUnit" to jsonString("nanoseconds"),
        "precision" to Histogram.PRECISION.toString(),
        "count" to count.toString(),
        "ok" to ok.toString(),
        "failed" to failed.toString(),
        "behind" to behind.toJson(depth = 1),
        "arrivals" to arrivals.toJson(depth = 1),
        "steps" to steps.values.jsonArray(depth = 1) { it.toJson(depth = 2) },
    ),
) + "\n"

private fun StepStats.toJson(depth: Int): String = jsonObject(
    depth = depth,
    fields = listOf(
        "name" to jsonString(name),
        "count" to count.toString(),
        "ok" to ok.toString(),
        "failed" to failed.toString(),
        "unmatched" to unmatched.toString(),
        "inFlight" to inFlight.toString(),
        "serviceTime" to serviceTime.toJson(depth + 1),
        "responseTime" to responseTime.toJson(depth + 1),
        "failures" to failures.entries.jsonArray(depth + 1) { (reason, seen) ->
            jsonObject(depth + 2, listOf("reason" to jsonString(reason), "count" to seen.toString()))
        },
    ),
)

private fun Arrivals.toJson(depth: Int): String = jsonObject(
    depth = depth,
    fields = listOf(
        "count" to count.toString(),
        "mean" to mean.inWholeNanoseconds.toString(),
        "cov" to cov.toString(),
    ),
)

private fun Timing.toJson(depth: Int): String = jsonObject(
    depth = depth,
    fields = listOf(
        "count" to count.toString(),
        "p50" to p50.inWholeNanoseconds.toString(),
        "p95" to p95.inWholeNanoseconds.toString(),
        "p99" to p99.inWholeNanoseconds.toString(),
        "p999" to p999.toJson(),
        "max" to max.inWholeNanoseconds.toString(),
    ),
)

// Null rather than a missing key: a step too thin to have measured its tail
// still has a tail field, and `count` beside it says why it is empty.
private fun Tail.toJson(): String = when (this) {
    is Tail.Measured -> duration.inWholeNanoseconds.toString()
    is Tail.Absent -> "null"
}

/**
 * Indented two spaces per level. The golden files are read in a diff, and a
 * one-line document is a diff nobody can review.
 */
private fun jsonObject(depth: Int, fields: List<Pair<String, String>>): String =
    if (fields.isEmpty()) "{}"
    else fields.joinToString(
        separator = ",\n",
        prefix = "{\n",
        postfix = "\n${indent(depth)}}",
    ) { (key, value) -> "${indent(depth + 1)}${jsonString(key)}: $value" }

private fun <T> Collection<T>.jsonArray(depth: Int, element: (T) -> String): String =
    if (isEmpty()) "[]"
    else joinToString(
        separator = ",\n",
        prefix = "[\n",
        postfix = "\n${indent(depth)}]",
    ) { "${indent(depth + 1)}${element(it)}" }

private fun indent(depth: Int): String = INDENT.repeat(depth)

private fun jsonString(value: String): String =
    value.map(::escaped).joinToString(separator = "", prefix = "\"", postfix = "\"")

private fun escaped(char: Char): String = when (char) {
    '"' -> "\\\""

    '\\' -> "\\\\"

    '\n' -> "\\n"

    '\r' -> "\\r"

    '\t' -> "\\t"

    // A failure reason is whatever the target said, and this payload is inlined
    // in a `<script>` element. Escaping these three means no reason can close
    // it; the value is unchanged, and `JSON.parse` reads it back whole.
    '<', '>', '&' -> unicodeEscape(char)

    else -> if (char < ' ') unicodeEscape(char) else char.toString()
}

private fun unicodeEscape(char: Char): String =
    "\\u" + char.code.toString(HEX_RADIX).padStart(UNICODE_ESCAPE_DIGITS, '0')

private const val INDENT = "  "
private const val HEX_RADIX = 16
private const val UNICODE_ESCAPE_DIGITS = 4
