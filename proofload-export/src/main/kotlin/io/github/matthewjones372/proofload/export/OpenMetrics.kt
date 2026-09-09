package io.github.matthewjones372.proofload.export

import io.github.matthewjones372.proofload.Histogram
import io.github.matthewjones372.proofload.RunResult
import io.github.matthewjones372.proofload.StepStats
import io.github.matthewjones372.proofload.Timing
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * What this run measured, as an OpenMetrics exposition.
 *
 * For a Pushgateway or a textfile collector to pick up once the run is over,
 * which is why there are no timestamps: both of those attach their own, and a
 * scrape that happened while requests were departing would have put a
 * serialisation pass on the injector.
 *
 * [run] tells ten runs on one dashboard apart, and defaults to when this one
 * started — the only thing a result carries that separates it from another.
 * Not a generated id: nothing else here has one, so a reader could not join it
 * to anything.
 *
 * **Measurements only.** The plan, the goals and their verdicts, the intervals,
 * the steady segment and every "cannot tell" stay in the report. A series
 * meaning "this might be noise" is a series that gets alerted on as though it
 * were not.
 */
public fun RunResult.openMetrics(run: String = startedAt.toString()): String =
    (
        latencyFamily(run) +
            distributionFamily("proofload_behind_seconds", BEHIND_HELP, behind, mapOf("run" to run)) +
            distributionFamily("proofload_hiccups_seconds", HICCUPS_HELP, hiccups, mapOf("run" to run)) +
            requestFamily(run) +
            failureFamily(run) +
            machineFamily() +
            listOf("# EOF")
        ).joinToString(separator = "\n", postfix = "\n")

/** Writes [openMetrics] to [path], creating the directories above it, and returns the path written. */
public fun RunResult.writeOpenMetrics(path: Path, run: String = startedAt.toString()): Path {
    path.parent?.let { Files.createDirectories(it) }
    return Files.writeString(path, openMetrics(run), Charsets.UTF_8)
}

/**
 * One histogram per step per side per clock. Four questions, and a family that
 * added them together would answer none of them.
 */
private fun RunResult.latencyFamily(run: String): List<String> {
    val series = steps.values.sortedBy { it.name }.flatMap { step -> step.sides(run) }
    if (series.isEmpty()) return emptyList()
    return listOf(
        "# TYPE proofload_latency_seconds histogram",
        "# UNIT proofload_latency_seconds seconds",
        "# HELP proofload_latency_seconds $LATENCY_HELP",
    ) + series.flatMap { (labels, timing) -> bucketLines("proofload_latency_seconds", labels, timing) }
}

private fun StepStats.sides(run: String): List<Pair<Map<String, String>, Timing>> = listOf(
    mapOf("run" to run, "step" to name, "outcome" to "ok", "clock" to "service") to ok.serviceTime,
    mapOf("run" to run, "step" to name, "outcome" to "ok", "clock" to "response") to ok.responseTime,
    mapOf("run" to run, "step" to name, "outcome" to "failed", "clock" to "service") to failed.serviceTime,
    mapOf("run" to run, "step" to name, "outcome" to "failed", "clock" to "response") to failed.responseTime,
).filter { (_, timing) -> timing.count > 0L }

private fun distributionFamily(
    name: String,
    help: String,
    timing: Timing,
    labels: Map<String, String>,
): List<String> {
    if (timing.count == 0L) return emptyList()
    return listOf(
        "# TYPE $name histogram",
        "# UNIT $name seconds",
        "# HELP $name $help",
    ) + bucketLines(name, labels, timing)
}

/**
 * The buckets, cumulative and ending in `+Inf`, then the count.
 *
 * Cumulative because that is what the format means by a bucket: `le` is "less
 * than or equal", so each line carries everything below it. The boundaries are
 * this histogram's own — nothing is re-bucketed to round numbers, which would
 * move counts across boundaries that were measured.
 *
 * No `_sum`. Nothing here adds latencies up, and a sum divided by a count is
 * the mean this repository refuses to print; emitting one would mean computing
 * it from bucket tops, which is a number nobody measured.
 */
private fun bucketLines(name: String, labels: Map<String, String>, timing: Timing): List<String> {
    var running = 0L
    val buckets = timing.distribution.map { bucket ->
        running += bucket.count
        "${name}_bucket${labels.and("le" to bucket.upperBound.inWholeNanoseconds.asSeconds())} $running"
    }
    return buckets +
        "${name}_bucket${labels.and("le" to "+Inf")} ${timing.count}" +
        "${name}_count${labels.asLabels()} ${timing.count}"
}

private fun RunResult.requestFamily(run: String): List<String> {
    if (steps.isEmpty()) return emptyList()
    return listOf(
        "# TYPE proofload_requests counter",
        "# HELP proofload_requests $REQUESTS_HELP",
    ) + steps.values.sortedBy { it.name }.map { step ->
        "proofload_requests_total${mapOf("run" to run, "step" to step.name).asLabels()} ${step.count}"
    }
}

/**
 * A series per reason, which is safe to do because a reason is a value with a
 * bounded set of them: the recorder caps how many one step will keep, so this
 * cannot become a series per request.
 */
private fun RunResult.failureFamily(run: String): List<String> {
    val counted = steps.values.sortedBy { it.name }.flatMap { step ->
        step.failed.reasons.entries.sortedBy { it.key.described }.map { (reason, count) ->
            mapOf("run" to run, "step" to step.name, "reason" to reason.described) to count
        }
    }
    if (counted.isEmpty()) return emptyList()
    return listOf(
        "# TYPE proofload_failures counter",
        "# HELP proofload_failures $FAILURES_HELP",
    ) + counted.map { (labels, count) -> "proofload_failures_total${labels.asLabels()} $count" }
}

/** What measured it, so a series from a laptop is not laid over one from a runner. */
private fun RunResult.machineFamily(): List<String> = listOf(
    "# TYPE proofload_machine info",
    "# HELP proofload_machine $MACHINE_HELP",
    "proofload_machine_info" +
        mapOf(
            "cores" to machine.cores.toString(),
            "jdk" to machine.jdk,
            "os" to machine.os,
            "arch" to machine.arch,
        ).asLabels() + " 1",
)

private fun Map<String, String>.and(extra: Pair<String, String>): String = (this + extra).asLabels()

// Comma-separated with no space: the grammar has no room for one, and a
// collector that accepted it would be being generous rather than correct.
private fun Map<String, String>.asLabels(): String =
    if (isEmpty()) "" else entries.joinToString(separator = ",", prefix = "{", postfix = "}") { (name, value) ->
        """$name="${value.escaped()}""""
    }

// A label value is a quoted string, so a reason or a step name carrying either
// of these would end the value early and the line would parse as something
// else. The format names these three and no others.
private fun String.escaped(): String =
    replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

/**
 * Nanoseconds as seconds, exactly.
 *
 * `BigDecimal` rather than a `Double` format: a boundary printed to fewer
 * digits than it has would put samples in a bucket they were not counted in,
 * and one printed in exponent notation is not what the grammar wants.
 */
private fun Long.asSeconds(): String = BigDecimal(this).movePointLeft(NANO_DIGITS).stripTrailingZeros().toPlainString()

private const val NANO_DIGITS = 9

private val LATENCY_HELP =
    "What the target took, and what it took counted from the departure the profile promised. " +
        "The boundaries are the histogram's own: every sample is counted at the top of the bucket it " +
        "fell in, so a percentile read off these is good to " +
        // To the two figures the rest of this tool quotes a precision in; a
        // third would be a digit claiming more than the bucket does.
        "${String.format(Locale.ROOT, "%.2f", Histogram.PRECISION * PERCENT)}% and no better. " +
        "histogram_quantile() interpolates inside a " +
        "bucket, which these numbers do not support: read it as the bucket top it lands in."

private const val BEHIND_HELP =
    "How late this run's departures were against the schedule the profile promised. Read it before any " +
        "latency here: where this is large the load named was never offered, and every other series is " +
        "about a smaller experiment than the one asked for."

private const val HICCUPS_HELP =
    "What the injector's own JVM stalled for while it measured, off the timed path. A tail smaller than " +
        "this is the measuring process rather than the target."

private const val REQUESTS_HELP = "Requests recorded under each step, successful and failed together."

private const val FAILURES_HELP =
    "Failures by the reason the module that made the request gave. The set is bounded per step by the " +
        "recorder, so this cannot become a series per request."

private const val MACHINE_HELP =
    "What measured the run. A series from one machine laid over a series from another is a comparison " +
        "of two machines as much as of two builds."

private const val PERCENT = 100.0
