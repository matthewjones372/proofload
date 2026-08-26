package io.github.matthewjones372.kestrel.baseline

import io.github.matthewjones372.kestrel.Bucket
import io.github.matthewjones372.kestrel.Histogram
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.StepStats
import io.github.matthewjones372.kestrel.Timing
import io.github.matthewjones372.kestrel.timing
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * A run, kept so the next one can be compared to it.
 *
 * The buckets travel rather than the percentiles: an interval cannot be
 * recomputed from five numbers, and a baseline that cannot be compared honestly
 * is not worth keeping.
 *
 * The format is lines of tab-separated fields, written and read here. A format
 * that needed a parser would be a dependency in disguise, on the classpath of
 * everyone who wanted to compare two runs.
 */
fun RunResult.writeBaseline(path: Path): Path {
    Files.createDirectories(path.toAbsolutePath().parent)
    return Files.writeString(path, asBaseline())
}

internal fun RunResult.asBaseline(): String =
    (
        listOf("$MARKER\t$VERSION", "run\t$startedAt") +
            steps.values.flatMap { it.lines() }
        ).joinToString(separator = "\n", postfix = "\n")

private fun StepStats.lines(): List<String> =
    listOf("step\t${name.escaped()}\t$count\t$ok") +
        serviceTime.lines("service", name) +
        responseTime.lines("response", name)

private fun Timing.lines(clock: String, step: String): List<String> =
    distribution.map { bucket -> "$clock\t${step.escaped()}\t${bucket.upperBound.inWholeNanoseconds}\t${bucket.count}" }

/** Reads a baseline written by [writeBaseline]. */
fun readBaseline(path: Path): RunResult = parseBaseline(Files.readString(path))

internal fun parseBaseline(text: String): RunResult {
    val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
    val header = lines.firstOrNull()?.split(SEPARATOR).orEmpty()
    require(header.getOrNull(0) == MARKER && header.getOrNull(1) == VERSION) {
        "not a Kestrel baseline, or written by a different version: ${lines.firstOrNull()}"
    }

    val startedAt = lines.first { it.startsWith("run$SEPARATOR") }.split(SEPARATOR)[1]
    val counts = lines.filter { it.startsWith("step$SEPARATOR") }.map { it.split(SEPARATOR) }
    val buckets = lines.filter { it.startsWith("service$SEPARATOR") || it.startsWith("response$SEPARATOR") }
        .map { it.split(SEPARATOR) }
        .groupBy { it[0] to it[1].unescaped() }

    val steps = counts.associate { fields ->
        val name = fields[1].unescaped()
        name to StepStats(
            name = name,
            count = fields[2].toLong(),
            ok = fields[3].toLong(),
            // Reasons are not kept: a baseline exists to answer "did this get
            // slower", and a failure that mattered is in the run's own report.
            failures = emptyMap(),
            serviceTime = buckets["service" to name].orEmpty().asTiming(),
            responseTime = buckets["response" to name].orEmpty().asTiming(),
        )
    }

    return RunResult(startedAt = Instant.parse(startedAt), steps = steps, behind = Histogram().timing())
}

private fun List<List<String>>.asTiming(): Timing {
    val buckets = map { fields -> Bucket(fields[2].toLong().nanoseconds, fields[3].toLong()) }
    val count = buckets.sumOf { it.count }
    return Timing(
        count = count,
        p50 = buckets.at(count, HALF),
        p95 = buckets.at(count, NINETY_FIVE),
        p99 = buckets.at(count, NINETY_NINE),
        max = buckets.lastOrNull()?.upperBound ?: Duration.ZERO,
        distribution = buckets,
    )
}

private fun List<Bucket>.at(total: Long, share: Double): Duration {
    if (isEmpty()) return Duration.ZERO
    val wanted = maxOf(1L, Math.ceil(share * total).toLong())
    return asSequence()
        .runningFold(0L to first().upperBound) { (seen, _), bucket -> (seen + bucket.count) to bucket.upperBound }
        .first { (seen, _) -> seen >= wanted }
        .second
}

// A step name is whatever a scenario called it, and a tab in one would split a
// line into the wrong fields.
private fun String.escaped(): String = replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

private fun String.unescaped(): String = replace("\\t", "\t").replace("\\n", "\n").replace("\\\\", "\\")

private const val MARKER = "kestrel-baseline"
private const val VERSION = "1"
private const val SEPARATOR = "\t"
private const val HALF = 0.5
private const val NINETY_FIVE = 0.95
private const val NINETY_NINE = 0.99
