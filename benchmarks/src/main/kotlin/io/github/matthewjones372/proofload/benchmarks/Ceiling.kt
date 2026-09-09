package io.github.matthewjones372.proofload.benchmarks

import io.github.matthewjones372.proofload.Headroom
import io.github.matthewjones372.proofload.Progress
import io.github.matthewjones372.proofload.RunResult
import io.github.matthewjones372.proofload.Scenario
import io.github.matthewjones372.proofload.Timing
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.engine.run
import io.github.matthewjones372.proofload.fellBehind
import io.github.matthewjones372.proofload.http.exec
import io.github.matthewjones372.proofload.http.http
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.scenario
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What Proofload costs, measured rather than claimed, over two paths.
 *
 * One sweep's step touches no socket, so the only thing between the departure a
 * profile promised and the sample a recorder took is this tool: its scheduler,
 * its virtual threads, and its histograms. That is an upper bound, on a path
 * nobody runs.
 *
 * The other sends the shipped `proofload-http` step at a target in this process,
 * so what is added is the client and the loopback stack. The target's own
 * service time is inside that figure, which makes it a lower bound: the client
 * reaches at least the rate it names.
 */
fun main() {
    val withoutASocket = RATES.map(::measureNullStep)
    val overASocket = SOCKET_RATES.map(::measureOverASocket)

    val page = report(withoutASocket, overASocket)
    println(page)

    val into = Path.of("build/reports/proofload/ceiling.md")
    Files.createDirectories(into.parent)
    Files.writeString(into, page)
    println("written to ${into.toAbsolutePath()}")
}

private fun measureNullStep(rate: Int): Measured {
    // A step that does nothing measurable, so the row is the generator.
    val nothing = scenario("nothing") { exec("step") { } }

    // Thrown away: the first run pays for class loading and JIT, and charging
    // that to the lowest rate would report a ceiling that moves with the order
    // the rates happen to be in.
    // Silent: a row measuring this tool should not have this tool talking
    // over it, and a progress line is a comfort rather than a measurement.
    nothing.at(rate.perSecond, over = SWEEP_WARMUP).run(Progress.silent)

    return Measured(rate, nothing.at(rate.perSecond, over = SWEEP_WINDOW).run(Progress.silent), loadAverage())
}

private fun measureOverASocket(rate: Int): Measured {
    // The warm-up gets a target of its own, because the row reports what this
    // rate's target took and a shared one would answer for two windows.
    loopback { warming -> hitting(warming).at(rate.perSecond, over = SWEEP_WARMUP).run(Progress.silent) }

    return loopback { target ->
        val result = hitting(target).at(rate.perSecond, over = SWEEP_WINDOW).run(Progress.silent)
        Measured(rate, result, loadAverage(), served = target.served())
    }
}

/** The shipped step, unconfigured: the sweep measures what a user gets. */
private fun hitting(target: LoopbackTarget): Scenario = hitting(target.baseUrl)

/** The same step at a target this process cannot see into, which is the only difference. */
internal fun hitting(baseUrl: String): Scenario =
    scenario("over a socket") { exec(http.baseUrl(baseUrl).get("/")) }

internal class Measured(
    val rate: Int,
    val result: RunResult,
    /** What this machine was carrying while the row was measured. */
    val load: Double,
    /** What the target itself took, where a target was in the path. */
    val served: Timing? = null,
) {

    /**
     * Two verdicts, because the one the reports use cannot answer this
     * question on its own: `fellBehind` asks whether the backlog is large
     * against the response time it inflates, and the null step has no response
     * time to speak of, so it says yes at every rate. The budget below is what
     * actually decides the ceiling — a departure late by more than this is late
     * enough to show up in a real target's percentiles.
     */
    val keptSchedule: Boolean get() = result.behind.p50 <= SWEEP_BUDGET

    /** A refused request is not a request this tool sent at the rate it promised. */
    val answeredEverything: Boolean get() = result.failed == 0L

    /**
     * What the failures were, most common first. A count with no reason beside
     * it cannot say whether the tool ran out of sockets or the target stopped
     * answering, which is the whole question a row with failures raises.
     */
    val whyFailed: String get() = result.steps.values
        .flatMap { it.failed.reasons.entries }
        .groupingBy { it.key.described }
        .fold(0L) { running, entry -> running + entry.value }
        .entries
        .sortedByDescending { it.value }
        .joinToString(separator = "; ") { "${it.key} ${it.value.grouped()}" }
        .ifEmpty { NOTHING }

    val relativeVerdict: String get() = if (result.fellBehind()) "yes" else "no"

    /**
     * How close this process came to its own descriptor and port ceilings.
     *
     * The column the failure counts needed: a row with seventeen thousand
     * IOExceptions cannot say whose end they came from, and a peak against the
     * real limit implicates this process or clears it.
     */
    val room: String get() = listOf(result.limits.openFiles, result.limits.ports)
        .joinToString(separator = " | ") { it.readable() }
}

private fun Headroom.readable(): String = when (this) {
    is Headroom.Measured -> "${peak.grouped()} / ${limit.grouped()}"
    is Headroom.Absent -> NOTHING
}

internal fun report(withoutASocket: List<Measured>, overASocket: List<Measured>): String =
    (
        preamble() + overSocketSection(overASocket) + nullStepSection(withoutASocket) +
            footer(withoutASocket + overASocket)
        )
        .joinToString(separator = "\n", postfix = "\n")

private fun preamble(): List<String> = listOf(
    "# What this tool costs",
    "",
    "Two sweeps, one rule. Each row is a $SWEEP_WINDOW run at one rate, and a rate kept",
    "its schedule when the median departure left within $SWEEP_BUDGET of when it was due —",
    "the same criterion in both tables, so the two ceilings can be read against each",
    "other.",
    "",
    "`fellBehind()` is the rule the reports use: it asks whether the backlog is large",
    "against the response time it inflates. That is a different question, and it is",
    "reported beside the budget rather than instead of it.",
)

private fun overSocketSection(measured: List<Measured>): List<String> {
    val ceiling = measured.lastOrNull { it.keptSchedule && it.answeredEverything }
    return listOf(
        "",
        "## Over a socket",
        "",
        "The shipped `proofload-http` step — one blocking `send` per virtual thread through",
        "the pooled shared client — against a `com.sun.net.httpserver` target in this",
        "process, over loopback. Generator and target share this machine's cores, which",
        "is what a laptop run and a single CI runner both look like.",
        "",
        "**This ceiling is a lower bound.** The target's own service time is inside it,",
        "and `com.sun.net.httpserver` is not a fast server, so a rate this sweep did not",
        "reach may be the server's limit rather than the client's. The served columns are",
        "the evidence either way: a handler time that stays flat while lateness climbs",
        "says the client gave up first, and one that climbs with it says the server did.",
        "",
        "What a handler time cannot see is the connection path in front of it. A request",
        "refused, dropped or left unanswered before a handler ran is counted under Failed",
        "and nowhere else, so a rate that failed requests is not a rate this tool",
        "sustained: such a row cannot be the ceiling however well it kept its schedule.",
        "",
        "**Files** and **Ports** are the other half of that question, sampled a second",
        "while each row ran: the peak this process reached against its own descriptor",
        "limit, and sockets in TIME_WAIT against the ephemeral port range. A row whose",
        "failures came with a peak near either ceiling ran out of room at this end of the",
        "wire, and its failures are the generator's rather than the target's. The port",
        "reading is machine-wide — `tw` counts every socket on the host — so it is read",
        "against a range that is machine-wide too, and a busy neighbour inflates both.",
        "",
        "| Rate | Requests | Failed | Failed as | Behind p50 | Behind p99 | Behind max | " +
            "Served p50 | Served p99 | Files | Ports | p50 within $SWEEP_BUDGET | fellBehind() |",
        "|---:|---:|---:|:---|---:|---:|---:|---:|---:|---:|---:|:---:|:---:|",
    ) + measured.map { it.socketRow() } + listOf(
        "",
        ceiling?.let { "Ceiling over a socket: **${it.rate.grouped()} a second** on this machine, a lower bound." }
            ?: "No rate here kept its schedule with nothing failed.",
    )
}

private fun nullStepSection(measured: List<Measured>): List<String> {
    val ceiling = measured.lastOrNull { it.keptSchedule }
    return listOf(
        "",
        "## Without a socket",
        "",
        "A scenario whose only step returns immediately, so the gap between the departure",
        "the profile promised and the sample the recorder took is Proofload and nothing",
        "else. Nobody runs a step that touches nothing, so this ceiling is the upper",
        "bound the one above is measured under.",
        "",
        "The ceiling is read off the median, not the tail. The p99 column is not a",
        "function of rate — the lowest rates here can have worse tails than rates ten",
        "times higher — so it holds stalls on the machine as well as any backlog, and one",
        "run of each rate is not enough to separate the two.",
        "",
        "| Rate | Requests | Behind p50 | Behind p99 | Behind max | p50 within $SWEEP_BUDGET | fellBehind() |",
        "|---:|---:|---:|---:|---:|:---:|:---:|",
    ) + measured.map { it.nullStepRow() } + listOf(
        "",
        ceiling?.let { "Ceiling without a socket: **${it.rate.grouped()} a second** on this machine, an upper bound." }
            ?: "No rate here kept its schedule.",
    )
}

private fun footer(measured: List<Measured>): List<String> {
    val loads = measured.map { it.load }.filter { it >= 0.0 }
    val carrying = if (loads.isEmpty()) {
        "a load average this JVM could not read"
    } else {
        "a one-minute load average of ${loads.min().rounded()} to ${loads.max().rounded()} across the sweep"
    }
    return listOf("", "Measured on ${machine()}, under $carrying.")
}

internal fun Measured.socketRow(): String =
    "| ${rate.grouped()} | ${result.count.grouped()} | ${result.failed.grouped()} | $whyFailed | " +
        "${result.behind.p50.readable()} | ${result.behind.p99.readable()} | ${result.behind.max.readable()} | " +
        "${served?.p50.readable()} | ${served?.p99.readable()} | $room | " +
        "${if (keptSchedule) "yes" else "no"} | $relativeVerdict |"

private fun Measured.nullStepRow(): String =
    "| ${rate.grouped()} | ${result.count.grouped()} | ${result.behind.p50.readable()} | " +
        "${result.behind.p99.readable()} | ${result.behind.max.readable()} | " +
        "${if (keptSchedule) "yes" else "no"} | $relativeVerdict |"

/** What the machine was carrying, so a figure taken on a busy one says so. */
internal fun loadAverage(): Double = ManagementFactory.getOperatingSystemMXBean().systemLoadAverage

internal fun machine(): String =
    "${System.getProperty("os.name")} ${System.getProperty("os.arch")}, " +
        "${Runtime.getRuntime().availableProcessors()} processors, " +
        "JDK ${System.getProperty("java.version")}"

internal fun Double.rounded(): String = String.format(Locale.ROOT, "%.2f", this)

internal fun Duration?.readable(): String = this?.toString() ?: NOTHING

/** What a cell holds where there was nothing to measure. */
private const val NOTHING = "—"

internal fun Number.grouped(): String =
    toLong().toString().reversed().chunked(THOUSAND).joinToString(",").reversed()

private const val THOUSAND = 3

internal val SWEEP_WARMUP: Duration = 1.seconds

internal val SWEEP_WINDOW: Duration = 5.seconds

private val RATES = listOf(100, 250, 500, 1_000, 5_000, 10_000, 25_000, 50_000, 100_000)

/**
 * Lower than the null step's, and stopping short of it: every request here is a
 * connection, a handler thread and two syscalls, and a rate the loopback stack
 * cannot answer measures the queue it built rather than the generator.
 */
internal val SOCKET_RATES = listOf(100, 250, 500, 1_000, 2_500, 5_000, 10_000)

/**
 * How late a departure may be before the tool is inflating what it measures.
 * A millisecond is roughly the floor of what a real target's p99 moves by, so a
 * generator inside it cannot be blamed for a number a user reads.
 */
internal val SWEEP_BUDGET: Duration = 1.milliseconds
