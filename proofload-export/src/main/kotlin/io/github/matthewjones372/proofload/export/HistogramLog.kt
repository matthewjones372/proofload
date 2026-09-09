package io.github.matthewjones372.proofload.export

import io.github.matthewjones372.proofload.RunResult
import io.github.matthewjones372.proofload.Timing
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * What this run measured, as an HdrHistogram log.
 *
 * The format `HistogramLogAnalyzer` and `HistogramLogProcessor` read, so a
 * team's existing tooling can draw and re-percentile these numbers without
 * being told about this repository's own file format. Nothing round-trips
 * back: the baseline is still what a comparison reads.
 *
 * One tagged line per step per side per clock, because those are four
 * different questions and a tool that added them up would answer none of them.
 * The run's own lateness and the injector's own stalls get lines too, so the
 * honesty travels with the numbers rather than staying on the HTML page.
 *
 * Only the step summaries. The timeline is counted an eighth as wide, and one
 * file holding two precisions tells its reader nothing about which line is
 * which.
 */
public fun RunResult.histogramLog(): String =
    (headerLines() + tagLines()).joinToString(separator = "\n", postfix = "\n")

/** Writes [histogramLog] to [path], creating the directories above it, and returns the path written. */
public fun RunResult.writeHistogramLog(path: Path): Path {
    path.parent?.let { Files.createDirectories(it) }
    return Files.writeString(path, histogramLog(), Charsets.UTF_8)
}

private fun RunResult.headerLines(): List<String> = listOf(
    "#[Logged with proofload]",
    "#[StartTime: ${startedAt.epochSecond()} (seconds since epoch), $startedAt]",
    "#[BaseTime: ${startedAt.epochSecond()} (seconds since epoch)]",
    """"StartTimestamp","Interval_Length","Interval_Max","Interval_Compressed_Histogram"""",
)

/**
 * Every timing this run froze, under a tag naming what it is.
 *
 * A timing that counted nothing is left out rather than written empty: a line
 * with no samples behind it is a series a reader would go looking for a cause
 * of.
 */
private fun RunResult.tagLines(): List<String> =
    steps.values.sortedBy { it.name }.flatMap { step ->
        listOf(
            "${step.name}.ok.service" to step.ok.serviceTime,
            "${step.name}.ok.response" to step.ok.responseTime,
            "${step.name}.failed.service" to step.failed.serviceTime,
            "${step.name}.failed.response" to step.failed.responseTime,
        )
    }.plus(
        listOf("behind" to behind, "hiccups" to hiccups),
    ).mapNotNull { (tag, timing) -> timing.takeIf { it.count > 0L }?.let { line(tag, it) } }

/**
 * One interval covering the whole run, because that is what was frozen: a log
 * of one line per run rather than per second, which is the shape of the
 * measurement rather than a resolution invented for the file.
 */
private fun RunResult.line(tag: String, timing: Timing): String {
    val seconds = timeline.size.toDouble()
    return listOf(
        "Tag=${tag.withoutSpaces()}",
        0.0.asSeconds(),
        seconds.asSeconds(),
        (timing.max.inWholeNanoseconds / NANOS_PER_SECOND).asSeconds(),
        timing.asV2Base64(),
    ).joinToString(separator = ",")
}

// The format is comma-separated with a space-separated tag field, so a step
// somebody named with either would move the columns under it.
private fun String.withoutSpaces(): String = replace(Regex("[\\s,]+"), "_")

private fun Double.asSeconds(): String = String.format(Locale.ROOT, "%.3f", this)

private fun java.time.Instant.epochSecond(): String =
    String.format(Locale.ROOT, "%.3f", toEpochMilli() / MILLIS_PER_SECOND)

private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val MILLIS_PER_SECOND = 1_000.0
