package io.github.matthewjones372.kestrel.report

import io.github.matthewjones372.kestrel.Comparison
import io.github.matthewjones372.kestrel.Concurrency
import io.github.matthewjones372.kestrel.Difference
import io.github.matthewjones372.kestrel.Floor
import io.github.matthewjones372.kestrel.Headroom
import io.github.matthewjones372.kestrel.Histogram
import io.github.matthewjones372.kestrel.Plan
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.StepStats
import io.github.matthewjones372.kestrel.TIGHT
import io.github.matthewjones372.kestrel.Timing
import io.github.matthewjones372.kestrel.concurrency
import io.github.matthewjones372.kestrel.fellBehind
import io.github.matthewjones372.kestrel.heldScheduleFor
import io.github.matthewjones372.kestrel.inFlight
import io.github.matthewjones372.kestrel.offered
import io.github.matthewjones372.kestrel.ranOutOfRoom
import io.github.matthewjones372.kestrel.unanswered
import io.github.matthewjones372.kestrel.unmatched
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The run as one self-contained page: the data, the stylesheet and the script
 * in the same file, so it opens from a `file://` URL and uploads as a CI
 * artifact unchanged.
 */
public fun RunResult.toHtmlReport(
    comparison: Comparison? = null,
    floor: Floor? = null,
    differences: List<Difference> = emptyList(),
): String = documentLines(comparison, floor, differences).joinToString(separator = "\n", postfix = "\n")

/**
 * Writes [toHtmlReport] to [path], creating the directories above it, and
 * returns the path written.
 */
public fun RunResult.writeHtmlReport(
    path: Path,
    comparison: Comparison? = null,
    floor: Floor? = null,
    differences: List<Difference> = emptyList(),
): Path {
    path.parent?.let { Files.createDirectories(it) }
    return announce(Files.writeString(path, toHtmlReport(comparison, floor, differences), Charsets.UTF_8))
}

/**
 * Says where the report went, and returns it so a caller reads as it did.
 *
 * A run ends with a page somebody is meant to open, and a path returned to a
 * variable nobody prints is a page nobody finds. Absolute and as a `file:` URI
 * because the argument is usually relative to a working directory the reader is
 * not in, and because a terminal makes that shape clickable.
 */
internal fun announce(written: Path): Path {
    println("kestrel: report at ${written.toAbsolutePath().normalize().toUri()}")
    return written
}

private fun RunResult.documentLines(
    comparison: Comparison?,
    floor: Floor?,
    differences: List<Difference>,
): List<String> =
    listOf(
        headLines(),
        verdictLines(),
        steadyLines(),
        comparison.comparisonLines(floor),
        differences.differenceLines(),
        planLines(),
        cutShortLines(),
        lostLines(),
        behindLines(),
        totalsLines(),
        goodputLines(),
        floor.resolutionLines(),
        hiccupLines(),
        roomLines(),
        concurrencyLines(),
        attemptLines(),
        readingLines(),
        failedLines(),
        stepsLines(),
        timelineLines(),
        tailLines(),
        listOf("</main>"),
        dataLines(),
        scriptLines(),
    ).flatten()

/**
 * A run that stopped before its schedule did, naming both windows: every count
 * under it is over the shorter one, and a page printing only what it measured
 * reads exactly like a run that saw its window out.
 *
 * Absent otherwise, because a warning on every page is one readers skip. A run
 * legitimately outlasts its window while it waits out the users it started, and
 * a shortfall under [SHORTFALL] of the window is the timeline's own whole
 * seconds as readily as a run somebody stopped.
 */
private fun RunResult.cutShortLines(): List<String> {
    val asked = plan.plannedWindow
    val recorded = timeline.size.seconds
    if (timeline.isEmpty() || asked <= Duration.ZERO || asked - recorded < asked * SHORTFALL) return emptyList()

    return listOf(
        """  <p class="behind" id="kestrel-cut-short" role="status">""",
        "    <strong>Cut short.</strong> The schedule asked for ${asked.forPlan()} and the run recorded " +
            "${recorded.forPlan()}. Every number below is over the shorter window.",
        "  </p>",
    )
}

/**
 * Above the backlog warning and above every count on the page. A record that
 * never arrived is not a missing sample: the throughput underneath it is
 * counting work the target may never have finished.
 */
private fun RunResult.lostLines(): List<String> =
    if (unanswered.isEmpty()) emptyList()
    else listOf(
        """  <p class="behind" id="kestrel-lost" role="status">""",
        "    <strong>Records that never arrived.</strong> " +
            unanswered.joinToString(separator = "; ") { step ->
                "${step.name.escapedForHtml()} — ${step.unmatched.grouped()} unmatched, " +
                    "${step.inFlight.grouped()} in flight"
            } +
            ". An unmatched record is one the sink had the whole drain window to answer for and did not; " +
            "an in-flight one left too late to be given that window.",
        "  </p>",
    )

/**
 * What the machine can tell apart, wherever that is small enough to bound a
 * claim rather than replace one — a floor too large for any claim is said in
 * place of the comparison instead.
 */
private fun Floor?.resolutionLines(): List<String> =
    if (this == null || !supportsAClaim) emptyList()
    else listOf(
        """  <p class="note" id="kestrel-floor">Calibrated on this machine: differences under """ +
            "<strong>${resolution.asPercent()}</strong> are not resolvable here. The injector's own " +
            "stalls reached <strong>${hiccups.p99.forReport()}</strong> at p99.</p>",
    )

/**
 * First thing on the page when it applies, because every percentile below it
 * counts a wait this tool caused. Absent otherwise: a warning that is always
 * there is one nobody reads.
 */
private fun RunResult.behindLines(): List<String> =
    if (!fellBehind()) emptyList()
    else listOf(
        """  <p class="behind" id="kestrel-behind" role="status">""",
        "    <strong>Behind schedule.</strong> ${behind.p99.forReport()} late at p99, " +
            "${behind.max.forReport()} at worst. The response times below include that backlog." +
            whatLeft(),
        "  </p>",
    )

/**
 * What a reader does about it, which the warning above has never said.
 *
 * Two facts and no advice: the load that actually left, and how long the
 * schedule held before it went. Service time was measured from the departure
 * that happened, so it describes the target at the load that left — a smaller
 * experiment than the one asked for, and the one this page can still answer
 * for. Deriving "re-run at 1,900/s" from them is the reader's, because under a
 * ramp the rate that held is the profile's rate at that second and nothing
 * here knows it.
 */
private fun RunResult.whatLeft(): String {
    val offered = offered ?: return ""
    val held = heldScheduleFor?.let { " The schedule held for ${it.forPlan()}." }.orEmpty()
    return " Asked for ${offered.asked.perSecond.asRate()}; ${offered.left.perSecond.asRate()} left over " +
        "${offered.over.forPlan()}.$held Service times below are the target at that load."
}

private fun RunResult.headLines(): List<String> =
    listOf(
        "<!doctype html>",
        """<html lang="en">""",
        "<head>",
        """<meta charset="utf-8">""",
        """<meta name="viewport" content="width=device-width, initial-scale=1">""",
        "<title>Kestrel run — ${started()}</title>",
        "<style>",
    ) + REPORT_CSS.lines() + listOf(
        "</style>",
        "</head>",
        "<body>",
        "<main>",
        """  <header class="run">""",
        """    <div class="run-name">""",
        """      <span class="wordmark">Kestrel</span>""",
        "      <h1>${headline()}</h1>",
        "    </div>",
        """    <div class="run-meta">""",
        """      <p class="when">Started <time datetime="${started()}">${started()}</time></p>""",
        THEME_TOGGLE,
        "    </div>",
        "  </header>",
    )

/**
 * Every scenario the plan named, where it named any. A result assembled from
 * samples rather than run has no plan to name, and "Kestrel run" is still true
 * of it — as is naming one arm of two, which is why both are named.
 */
private fun RunResult.headline(): String =
    plan.arms
        .map { it.scenario }
        .filter { it.isNotBlank() }
        .takeIf { it.isNotEmpty() }
        ?.joinToString(separator = " + ") { it.escapedForHtml() }
        ?: "Kestrel run"

/**
 * Drawn rather than lettered, so it needs no font, and inert without the
 * script: a reader whose browser ran no JavaScript keeps the machine's own
 * theme and is shown nothing that promises otherwise.
 */
internal const val THEME_TOGGLE: String =
    """      <button type="button" id="theme-toggle" class="theme-toggle" hidden """ +
        """aria-label="Switch theme"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" """ +
        """stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" """ +
        """aria-hidden="true"><circle cx="12" cy="12" r="4"></circle><path d="M12 2v2"></path>""" +
        """<path d="M12 20v2"></path><path d="m4.9 4.9 1.4 1.4"></path><path d="m17.7 17.7 1.4 1.4"></path>""" +
        """<path d="M2 12h2"></path><path d="M20 12h2"></path><path d="m6.3 17.7-1.4 1.4"></path>""" +
        """<path d="m19.1 4.9-1.4 1.4"></path></svg></button>"""

private fun RunResult.started(): String = startedAt.toString().escapedForHtml()

private fun RunResult.totalsLines(): List<String> =
    listOf("""  <section class="totals" aria-label="Totals">""") +
        tile("Requests", count.grouped(), "") +
        tile("OK", ok.grouped(), " ok") +
        tile("Failed", failed.grouped(), " failed") +
        lostTiles() +
        tile("Behind schedule, p99", behind.p99OrNothing(), "") +
        hiccupTile() +
        listOf("  </section>")

// Absent when there is nothing to say: a run with no emit steps would otherwise
// carry two tiles reading zero on every page anyone ever opens.
private fun RunResult.lostTiles(): List<String> =
    if (unanswered.isEmpty()) emptyList()
    else tile("Unmatched", unmatched.grouped(), " failed") + tile("In flight", inFlight.grouped(), "")

/**
 * Beside the backlog rather than under the table, because the two are read
 * together: what the injector stalled for is the size of tail this machine can
 * produce on its own. Absent when nothing watched, which is every result
 * assembled from samples rather than run.
 */
private fun RunResult.hiccupTile(): List<String> =
    if (hiccups.count == 0L) emptyList() else tile("Injector stalled, p99", hiccups.p99.forReport(), "")

/**
 * Where a step went to the target more often than it was counted — a redirect
 * followed, or a retry — the trips behind the requests.
 *
 * Absent where every step went once, which is most runs: a line saying 480
 * requests took 480 attempts is one nobody needs. It is a note rather than a
 * column because it is true of some runs and no steps of most.
 */

/**
 * What the injector ran out of, where it did.
 *
 * Above the counts rather than beside them, because a run that exhausted its
 * own descriptors was failing requests at this end of the wire: the failures
 * below are about this process, and a reader who takes them for the target's
 * has been misled by the page. Absent where the run had room, and absent where
 * nothing was sampled — a page saying a limit was not measured on every
 * platform that cannot measure it is a line nobody reads.
 */

/**
 * The users this run had in flight, beside what its own throughput and latency
 * say it should have had.
 *
 * A note where the two agree and a warning where they do not, and the warning
 * names *this tool* rather than the target: L = λW is arithmetic over a window
 * that starts and ends empty, so a gap is a measurement that does not add up
 * and every number on the page is suspect until it is explained. A page that
 * blamed the target for it would be the misreading this check exists to catch.
 *
 * Silent where the run cannot be asked — a scenario that parks its users, a
 * run nobody sampled — because a reason nobody needed is a line nobody reads.
 */
private fun RunResult.concurrencyLines(): List<String> {
    val law = concurrency as? Concurrency.Measured ?: return emptyList()

    val sides = "${law.observed.round()} users were running; throughput times mean service time says " +
        "${law.fromServiceTime.round()}"
    val queue = if (law.backlog <= 0.0) {
        ""
    } else {
        " The generator was holding ${law.backlog.round()} requests of queue of its own."
    }
    return if (law.agrees) {
        listOf(
            """  <p class="note" id="kestrel-concurrency">Little's law holds on this run: $sides, """ +
                "a ratio of ${law.ratio.round()} across ${law.samples} " +
                "${if (law.samples == 1) "sample" else "samples"}.$queue</p>",
        )
    } else {
        listOf(
            """  <p class="behind" id="kestrel-concurrency" role="status">""",
            "    <strong>These numbers do not add up.</strong> $sides — a ratio of ${law.ratio.round()}. " +
                "Little's law is arithmetic over a settled window, so this is a fault in the measurement " +
                "rather than in the target, and every figure below it is suspect until it is explained.$queue",
            "  </p>",
        )
    }
}

private fun RunResult.roomLines(): List<String> {
    if (!ranOutOfRoom()) return emptyList()

    val tight = limits.all
        .mapNotNull { (name, headroom) ->
            (headroom as? Headroom.Measured)?.takeIf { it.used >= TIGHT }?.let {
                name to
                    it
            }
        }
        .joinToString(separator = ", ") { (name, at) ->
            "$name reached ${at.peak.grouped()} of ${at.limit.grouped()}"
        }
    return listOf(
        """  <p class="behind" id="kestrel-room" role="status">""",
        "    <strong>The injector ran out of room.</strong> $tight. Failures below are this process " +
            "hitting its own ceiling as readily as the target refusing work.",
        "  </p>",
    )
}

private fun RunResult.attemptLines(): List<String> {
    val retried = steps.values.filter { it.attempts > it.count }
    if (retried.isEmpty()) return emptyList()

    val named = retried.joinToString(separator = ", ") {
        "${it.name.escapedForHtml()} ${it.count.grouped()} requests, " +
            "${it.attempts.grouped()} attempts"
    }
    return listOf(
        """  <p class="note" id="kestrel-attempts">Some steps went to the target more than once per """ +
            "request — a redirect followed, or a retry: $named. The service time is the request's, and " +
            "the attempts are the trips behind it.</p>",
    )
}

private fun RunResult.hiccupLines(): List<String> =
    if (hiccups.count == 0L) emptyList()
    else listOf(
        """  <p class="note" id="kestrel-hiccups">The injector's own JVM stalled for """ +
            "${hiccups.p99.forReport()} at p99 and ${hiccups.max.forReport()} at worst, measured on a thread " +
            "no request ran on. A tail that size is this machine as readily as the target.</p>",
    )

private fun tile(label: String, value: String, extraClass: String): List<String> =
    listOf(
        """    <div class="tile$extraClass">""",
        """      <span class="tile-label">${label.escapedForHtml()}</span>""",
        """      <span class="tile-value">$value</span>""",
        "    </div>",
    )

private fun RunResult.stepsLines(): List<String> =
    listOf(
        """  <section class="steps">""",
        """    <div class="steps-head">""",
        "      <h2>Steps</h2>",
        """      <p class="mode">Showing <strong id="mode-name">service time</strong>. """ +
            """<button type="button" id="mode-toggle">Show response time</button></p>""",
        "    </div>",
    ) + tableLines() + chartLines() + listOf(
        """    <p class="note">Count is requests and reached is the users that got here, counted once """ +
            "each: a loop multiplies the first and a condition divides it, and a step that made few " +
            "requests because few users reached it is a different finding from one they each made few " +
            "at. Service time is what the target took; response time counts from the " +
            "departure the profile promised, so it carries the generator's own backlog. Percentiles are " +
            "the top of the histogram bucket a sample fell in, never a point interpolated between two: " +
            "each is good to ${Histogram.PRECISION.asPercent()}, and rounds away from the target rather " +
            "than towards it.</p>",
        "  </section>",
    )

private fun RunResult.chartLines(): List<String> =
    steps.values.flatMap { step -> step.serviceTime.distributionChart(step.name) }

private fun RunResult.tableLines(): List<String> {
    if (steps.isEmpty()) return listOf("""    <p class="empty">This run recorded no steps.</p>""")

    val columns = columnsFor(this)
    return listOf(
        """    <div class="table-scroll">""",
        "      <table>",
        "        <thead>",
        "          <tr>",
    ) + columns.map { (heading, numeric) ->
        val classes = if (numeric) """ class="num"""" else ""
        """            <th scope="col"$classes data-sort="${heading.sortKey()}" aria-sort="none">""" +
            """<button type="button">${heading.escapedForHtml()}</button></th>"""
    } + listOf(
        "          </tr>",
        "        </thead>",
        "        <tbody>",
    ) + steps.values.flatMap { it.rowLines(plan.armOf(it.name), columns.size) } + listOf(
        "        </tbody>",
        "      </table>",
        "    </div>",
    )
}

/**
 * Which arm sent a step, for a run that has more than one. A step name is
 * unique across a mix, so the arm holding the name is the arm that sent it, and
 * a name no arm planned belongs to none of them.
 */
private fun Plan.armOf(step: String): String? =
    if (arms.size < 2) null else arms.firstOrNull { step in it.steps }?.scenario ?: NOTHING_MEASURED

private fun StepStats.rowLines(arm: String?, columns: Int): List<String> =
    listOf(
        """          <tr class="step" data-step="${name.escapedForHtml()}"""" +
            (if (failed.reasons.isEmpty()) ">" else """ aria-expanded="false" tabindex="0">"""),
        """            <th scope="row">${name.escapedForHtml()}</th>""",
    ) + armCell(arm) + listOf(
        """            <td class="num">${count.grouped()}</td>""",
        """            <td class="num reached">${reached.orNothing()}</td>""",
        """            <td class="num ok">${ok.count.grouped()}</td>""",
        """            <td class="num failed">${failed.count.grouped()}</td>""",
        timeCell(serviceTime.p50, responseTime.p50),
        timeCell(serviceTime.p95, responseTime.p95),
        timeCell(serviceTime.p99, responseTime.p99, responseTime.exemplar(NINETY_NINTH)),
        timeCell(serviceTime.max, responseTime.max),
        "          </tr>",
    ) + reasonLines(columns)

// Only for a run that had more than one arm: a column repeating one scenario
// name down every row is noise on the page it is meant to be missing from.
private fun armCell(arm: String?): List<String> =
    if (arm == null) emptyList() else listOf("""            <td class="arm">${arm.escapedForHtml()}</td>""")

/**
 * Both times ride on the cell: the toggle swaps text the server already
 * formatted rather than reformatting nanoseconds in the browser, so there is
 * one implementation of "three significant figures" and it is the tested one.
 */
private fun timeCell(service: Duration, response: Duration, trace: String? = null): String {
    val attributes = listOf(
        """class="num time"""",
        """data-service="${service.forReport()}"""",
        """data-response="${response.forReport()}"""",
        """data-service-ns="${service.inWholeNanoseconds}"""",
        """data-response-ns="${response.inWholeNanoseconds}"""",
        // On the cell rather than in it: a trace id is thirty-two characters
        // and would be wider than the column it sits in, so it travels as an
        // attribute a reader can copy and a tooltip they can see.
        trace?.let { """data-trace="${it.escapedForHtml()}" title="trace ${it.escapedForHtml()}"""" },
    ).filterNotNull().joinToString(separator = " ")
    return "            <td $attributes>${service.forReport()}</td>"
}

private fun StepStats.reasonLines(columns: Int): List<String> =
    if (failed.reasons.isEmpty()) emptyList()
    else listOf(
        """          <tr class="reasons" data-for="${name.escapedForHtml()}">""",
        """            <td colspan="$columns">""",
        """              <ul class="reason-list">""",
    ) + failed.reasons.map { (reason, seen) ->
        """                <li><span class="reason">${reason.described.escapedForHtml()}</span>""" +
            """<span class="reason-count">${seen.grouped()}</span></li>"""
    } + listOf(
        "              </ul>",
        "            </td>",
        "          </tr>",
    )

private fun RunResult.dataLines(): List<String> =
    listOf("""<script type="application/json" id="kestrel-run">""") +
        toJson().trimEnd().lines() +
        listOf("</script>")

private fun RunResult.scriptLines(): List<String> =
    listOf("<script>") + THEME_JS.lines() + REPORT_JS.lines() + listOf("</script>", "</body>", "</html>")

private fun String.sortKey(): String = substringBefore(" (").lowercase().replace(" ", "-")

private fun Timing.p99OrNothing(): String = if (count == 0L) NOTHING_MEASURED else p99.forReport()

// A run recorded by something that did not count users has no reaches to print,
// which is not the same fact as a step nobody reached: a zero here would be the
// page claiming a measurement nothing took.
private fun Long.orNothing(): String = if (this == 0L) NOTHING_MEASURED else grouped()

internal fun String.escapedForHtml(): String = map(::htmlEscaped).joinToString(separator = "")

private fun htmlEscaped(char: Char): String = when (char) {
    '&' -> "&amp;"
    '<' -> "&lt;"
    '>' -> "&gt;"
    '"' -> "&quot;"
    '\'' -> "&#39;"
    else -> char.toString()
}

/**
 * The percentile columns say how many samples sit at or beyond them, for the
 * thinnest step in the run. A p99 written without that reads like a fact when
 * it is sometimes five requests.
 */
private fun columnsFor(result: RunResult): List<Pair<String, Boolean>> {
    val columns = if (result.plan.arms.size < 2) COLUMNS else COLUMNS.take(1) + ("Arm" to false) + COLUMNS.drop(1)
    val thinnest = result.steps.values.minByOrNull { it.count } ?: return columns
    return columns.map { (heading, numeric) ->
        when (heading) {
            "p95" -> "p95 (${samplesBeyond(thinnest, PERCENTILE_95).grouped()})" to numeric
            "p99" -> "p99 (${samplesBeyond(thinnest, PERCENTILE_99).grouped()})" to numeric
            else -> heading to numeric
        }
    }
}

private const val PERCENTILE_95 = 95.0
private const val PERCENTILE_99 = 99.0

/** Each column, and whether it holds a number. */
private val COLUMNS = listOf(
    "Step" to false,
    "Count" to true,
    "Reached" to true,
    "OK" to true,
    "Failed" to true,
    "p50" to true,
    "p95" to true,
    "p99" to true,
    "Max" to true,
)

internal const val NOTHING_MEASURED: String = "—"

/** Under this share of the window, a shortfall is the timeline's own whole seconds rather than a run that stopped. */
private const val SHORTFALL = 0.1

/** The percentile the table's p99 column reports, so the exemplar names a request from that bucket. */
private const val NINETY_NINTH = 99.0

/** One place a share or a ratio is rounded for reading. */
private fun Double.round(): String = String.format(Locale.ROOT, "%.2f", this)
