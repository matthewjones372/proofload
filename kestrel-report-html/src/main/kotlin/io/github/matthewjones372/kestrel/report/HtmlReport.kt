package io.github.matthewjones372.kestrel.report

import io.github.matthewjones372.kestrel.Comparison
import io.github.matthewjones372.kestrel.Floor
import io.github.matthewjones372.kestrel.Histogram
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.StepStats
import io.github.matthewjones372.kestrel.Timing
import io.github.matthewjones372.kestrel.fellBehind
import io.github.matthewjones372.kestrel.inFlight
import io.github.matthewjones372.kestrel.unanswered
import io.github.matthewjones372.kestrel.unmatched
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration

/**
 * The run as one self-contained page: the data, the stylesheet and the script
 * in the same file, so it opens from a `file://` URL and uploads as a CI
 * artifact unchanged.
 */
public fun RunResult.toHtmlReport(comparison: Comparison? = null, floor: Floor? = null): String =
    documentLines(comparison, floor).joinToString(separator = "\n", postfix = "\n")

/**
 * Writes [toHtmlReport] to [path], creating the directories above it, and
 * returns the path written.
 */
public fun RunResult.writeHtmlReport(
    path: Path,
    comparison: Comparison? = null,
    floor: Floor? = null,
): Path {
    path.parent?.let { Files.createDirectories(it) }
    return Files.writeString(path, toHtmlReport(comparison, floor), Charsets.UTF_8)
}

private fun RunResult.documentLines(comparison: Comparison?, floor: Floor?): List<String> =
    listOf(
        headLines(),
        verdictLines(),
        comparison.comparisonLines(floor),
        plan.headerLines(arrivals),
        lostLines(),
        behindLines(),
        totalsLines(),
        goodputLines(),
        floor.resolutionLines(),
        hiccupLines(),
        readingLines(),
        stepsLines(),
        timelineLines(),
        tailLines(),
        listOf("</main>"),
        dataLines(),
        scriptLines(),
    ).flatten()

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
            "${behind.max.forReport()} at worst. The response times below include that backlog.",
        "  </p>",
    )

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
        "    <h1>Kestrel run</h1>",
        """    <p class="when">Started <time datetime="${started()}">${started()}</time></p>""",
        "  </header>",
    )

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
        """    <p class="note">Service time is what the target took; response time counts from the """ +
            "departure the profile promised, so it carries the generator's own backlog. Percentiles are " +
            "the top of the histogram bucket a sample fell in, never a point interpolated between two: " +
            "each is good to ${Histogram.PRECISION.asPercent()}, and rounds away from the target rather " +
            "than towards it.</p>",
        "  </section>",
    )

private fun RunResult.chartLines(): List<String> =
    steps.values.flatMap { step -> step.serviceTime.distributionChart(step.name) }

private fun RunResult.tableLines(): List<String> =
    if (steps.isEmpty()) listOf("""    <p class="empty">This run recorded no steps.</p>""")
    else listOf(
        """    <div class="table-scroll">""",
        "      <table>",
        "        <thead>",
        "          <tr>",
    ) + columnsFor(this).map { (heading, numeric) ->
        val classes = if (numeric) """ class="num"""" else ""
        """            <th scope="col"$classes data-sort="${heading.sortKey()}" aria-sort="none">""" +
            """<button type="button">${heading.escapedForHtml()}</button></th>"""
    } + listOf(
        "          </tr>",
        "        </thead>",
        "        <tbody>",
    ) + steps.values.flatMap { it.rowLines() } + listOf(
        "        </tbody>",
        "      </table>",
        "    </div>",
    )

private fun StepStats.rowLines(): List<String> =
    listOf(
        """          <tr class="step" data-step="${name.escapedForHtml()}"""" +
            (if (failures.isEmpty()) ">" else """ aria-expanded="false" tabindex="0">"""),
        """            <th scope="row">${name.escapedForHtml()}</th>""",
        """            <td class="num">${count.grouped()}</td>""",
        """            <td class="num ok">${ok.grouped()}</td>""",
        """            <td class="num failed">${failed.grouped()}</td>""",
        timeCell(serviceTime.p50, responseTime.p50),
        timeCell(serviceTime.p95, responseTime.p95),
        timeCell(serviceTime.p99, responseTime.p99),
        timeCell(serviceTime.max, responseTime.max),
        "          </tr>",
    ) + reasonLines()

/**
 * Both times ride on the cell: the toggle swaps text the server already
 * formatted rather than reformatting nanoseconds in the browser, so there is
 * one implementation of "three significant figures" and it is the tested one.
 */
private fun timeCell(service: Duration, response: Duration): String {
    val attributes = listOf(
        """class="num time"""",
        """data-service="${service.forReport()}"""",
        """data-response="${response.forReport()}"""",
        """data-service-ns="${service.inWholeNanoseconds}"""",
        """data-response-ns="${response.inWholeNanoseconds}"""",
    ).joinToString(separator = " ")
    return "            <td $attributes>${service.forReport()}</td>"
}

private fun StepStats.reasonLines(): List<String> =
    if (failures.isEmpty()) emptyList()
    else listOf(
        """          <tr class="reasons" data-for="${name.escapedForHtml()}">""",
        """            <td colspan="${COLUMNS.size}">""",
        """              <ul class="reason-list">""",
    ) + failures.map { (reason, seen) ->
        """                <li><span class="reason">${reason.escapedForHtml()}</span>""" +
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
    listOf("<script>") + REPORT_JS.lines() + listOf("</script>", "</body>", "</html>")

private fun String.sortKey(): String = substringBefore(" (").lowercase().replace(" ", "-")

private fun Timing.p99OrNothing(): String = if (count == 0L) NOTHING_MEASURED else p99.forReport()

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
    val thinnest = result.steps.values.minByOrNull { it.count } ?: return COLUMNS
    return COLUMNS.map { (heading, numeric) ->
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
    "OK" to true,
    "Failed" to true,
    "p50" to true,
    "p95" to true,
    "p99" to true,
    "Max" to true,
)

private const val NOTHING_MEASURED = "—"
