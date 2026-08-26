package io.github.matthewjones372.kestrel.benchmarks

import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.engine.run
import io.github.matthewjones372.kestrel.fellBehind
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.scenario
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What Kestrel costs, measured rather than claimed.
 *
 * The step touches no socket, so the only thing between the departure a profile
 * promised and the sample a recorder took is this tool: its scheduler, its
 * virtual threads, and its histograms. The rate at which that gap stops being
 * negligible is the ceiling, and past it every latency Kestrel reports is
 * partly its own.
 */
fun main() {
    val measured = RATES.map { rate -> measure(rate) }
    val ceiling = measured.lastOrNull { it.keptSchedule }

    val table = report(measured, ceiling)
    println(table)

    val into = Path.of("build/reports/kestrel/ceiling.md")
    Files.createDirectories(into.parent)
    Files.writeString(into, table)
    println("written to ${into.toAbsolutePath()}")
}

private fun measure(rate: Int): Measured {
    // A step that does nothing measurable, so the row is the generator.
    val nothing = scenario("nothing") { exec("step") { } }

    // Thrown away: the first run pays for class loading and JIT, and charging
    // that to the lowest rate would report a ceiling that moves with the order
    // the rates happen to be in.
    nothing.at(rate.perSecond, over = WARMUP).run()

    val result = nothing.at(rate.perSecond, over = WINDOW).run()
    return Measured(rate, result)
}

private class Measured(val rate: Int, val result: RunResult) {

    /**
     * Two verdicts, because the one the reports use cannot answer this
     * question on its own: `fellBehind` asks whether the backlog is large
     * against the response time it inflates, and the step here has no response
     * time to speak of, so it says yes at every rate. The budget below is what
     * actually decides the ceiling — a departure late by more than this is late
     * enough to show up in a real target's percentiles.
     */
    val keptSchedule: Boolean get() = result.behind.p50 <= BUDGET

    val relativeVerdict: String get() = if (result.fellBehind()) "yes" else "no"
}

private fun report(measured: List<Measured>, ceiling: Measured?): String =
    (
        listOf(
            "# What this tool costs",
            "",
            "Each row is a $WINDOW run of a scenario whose only step returns immediately,",
            "so the gap between the departure the profile promised and the sample the",
            "recorder took is Kestrel and nothing else.",
            "",
            "The ceiling is read off the median, not the tail. The p99 column does not",
            "scale with rate — a run at a hundred a second has the same multi-millisecond",
            "worst cases as one at a hundred thousand — so what it measures is occasional",
            "stalls on the machine rather than a generator falling behind, and one run of",
            "each rate is not enough to characterise it.",
            "",
            "`fellBehind()` is the rule the reports use: it asks whether the backlog is",
            "large against the response time it inflates. A step that returns immediately",
            "has no response time to inflate, so that column says no at every rate and the",
            "$BUDGET budget is what picks the ceiling here.",
            "",
            "| Rate | Requests | Behind p50 | Behind p99 | Behind max | p50 within $BUDGET | fellBehind() |",
            "|---:|---:|---:|---:|---:|:---:|:---:|",
        ) + measured.map { it.row() } + listOf(
            "",
            ceiling?.let { "Ceiling: **${it.rate.grouped()} a second** on this machine." }
                ?: "No rate here kept its schedule.",
            "",
            "Measured on ${machine()}.",
        )
        ).joinToString(separator = "\n", postfix = "\n")

private fun Measured.row(): String =
    "| ${rate.grouped()} | ${result.count.grouped()} | ${result.behind.p50.readable()} | " +
        "${result.behind.p99.readable()} | ${result.behind.max.readable()} | " +
        "${if (keptSchedule) "yes" else "no"} | $relativeVerdict |"

private fun machine(): String =
    "${System.getProperty("os.name")} ${System.getProperty("os.arch")}, " +
        "${Runtime.getRuntime().availableProcessors()} processors, " +
        "JDK ${System.getProperty("java.version")}"

private fun Duration.readable(): String = toString()

private fun Number.grouped(): String =
    toLong().toString().reversed().chunked(THOUSAND).joinToString(",").reversed()

private const val THOUSAND = 3

private val WARMUP: Duration = 1.seconds

private val WINDOW: Duration = 5.seconds

private val RATES = listOf(100, 250, 500, 1_000, 5_000, 10_000, 25_000, 50_000, 100_000)

/**
 * How late a departure may be before the tool is inflating what it measures.
 * A millisecond is roughly the floor of what a real target's p99 moves by, so a
 * generator inside it cannot be blamed for a number a user reads.
 */
private val BUDGET: Duration = 1.milliseconds
