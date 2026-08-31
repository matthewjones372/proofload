package io.github.matthewjones372.kestrel.report

import io.github.matthewjones372.kestrel.Capacity
import io.github.matthewjones372.kestrel.Rung
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.time.Duration

/**
 * The search as one self-contained page: every rung it ran, what each one was
 * judged to be, and which rate came out of it.
 *
 * The curve is the point. A rate on its own is a number to argue with; the
 * rungs either side of it are what say whether the target degrades gently or
 * falls over, and whether the answer sits on a knee or on a plateau.
 */
public fun Capacity.toHtmlReport(): String =
    documentLines().joinToString(separator = "\n", postfix = "\n")

/** Writes [toHtmlReport] to [path], creating the directories above it, and returns the path written. */
public fun Capacity.writeHtmlReport(path: Path): Path {
    path.parent?.let { Files.createDirectories(it) }
    return announce(Files.writeString(path, toHtmlReport(), Charsets.UTF_8))
}

private fun Capacity.documentLines(): List<String> =
    listOf(
        headLines(),
        voidLines(),
        operatingLines(),
        curveChartLines(),
        tableLines(),
        noteLines(),
    ).flatten()

private fun Capacity.headLines(): List<String> =
    listOf(
        "<!doctype html>",
        """<html lang="en">""",
        "<head>",
        """<meta charset="utf-8">""",
        """<meta name="viewport" content="width=device-width, initial-scale=1">""",
        "<title>Kestrel capacity — ${scenario()}</title>",
        "<style>",
    ) + REPORT_CSS.lines() + listOf(
        "</style>",
        "</head>",
        "<body>",
        "<main>",
        """  <header class="run">""",
        """    <div class="run-name">""",
        """      <span class="wordmark">Kestrel</span>""",
        "      <h1>The rate it sustains</h1>",
        "    </div>",
        """    <div class="run-meta">""",
        """      <p class="when">${scenario()} — ${curve.size} ${"rung".plural(curve.size)}${started()}</p>""",
        THEME_TOGGLE,
        "    </div>",
        "  </header>",
    )

private fun Capacity.scenario(): String =
    (curve.firstOrNull()?.result?.plan?.scenario ?: "no scenario").escapedForHtml()

private fun Capacity.started(): String =
    curve.firstOrNull()?.result?.startedAt?.toString()?.escapedForHtml()
        ?.let { """, started <time datetime="$it">$it</time>""" }
        .orEmpty()

/**
 * First thing on the page when it applies. A void rung is the one outcome a
 * reader must not skim past: it is the generator's ceiling, and reading it as
 * the target's is the mistake the whole search exists to avoid.
 */
private fun Capacity.voidLines(): List<String> {
    val voidRung = curve.firstOrNull { it.outcome == Rung.Outcome.Void } ?: return emptyList()
    return listOf(
        """  <p class="behind" id="kestrel-void" role="status">""",
        "    <strong>Stopped on a void rung.</strong> At ${voidRung.rate.perSecond.asRate()} the profile " +
            "promised a departure every ${voidRung.result.plan.plannedInterval.forReport()}, and the " +
            "injector's own lateness reached ${voidRung.result.behind.p99.forReport()} at p99 — a whole " +
            "departure behind at the tail. That rung is void: the load was never offered, so nothing " +
            "there was learned about the target, and the search stopped rather than climb further.",
        "  </p>",
    )
}

private fun Capacity.operatingLines(): List<String> =
    if (curve.isEmpty()) listOf("""  <p class="empty">This search ran no rungs.</p>""", "</main>", "</body>", "</html>")
    else listOf(
        """  <section class="operating" aria-label="The operating point">""",
        """    <p class="operating-rate">${headline()}</p>""",
        """    <p class="operating-limit">${limit()}</p>""",
        "  </section>",
    )

private fun Capacity.headline(): String = when {
    voided -> "<strong>no answer</strong>"
    rate == null -> "<strong>nothing sustained</strong>"
    else -> "<strong>${rate?.perSecond?.asRate()}</strong> sustained"
}

private fun Capacity.limit(): String {
    val stopped = limitedBy?.described?.escapedForHtml()
    val firstFailing = curve.firstOrNull { it.outcome == Rung.Outcome.Failed }?.rate?.perSecond?.asRate()
    val judged = curve.lastOrNull { it.outcome != Rung.Outcome.Void }?.rate?.perSecond?.asRate()
    return when {
        voided ->
            "The highest rate this search judged was $judged, which is a floor the generator reached " +
                "rather than a ceiling the target could not pass."

        stopped != null -> "Limited by <strong>$stopped</strong>, first missed at $firstFailing."

        else -> "No rung missed a goal, so whatever the ceiling is, it is above $judged."
    }
}

/**
 * Response time against rate, one point per rung.
 *
 * Straight lines between the rungs that ran, and nothing drawn between them:
 * the shape of a curve through a knee is exactly what a load test cannot
 * interpolate honestly.
 */
private fun Capacity.curveChartLines(): List<String> {
    if (curve.isEmpty()) return emptyList()
    val fastest = curve.maxOf { it.rate.perSecond }.takeIf { it > 0.0 } ?: return emptyList()
    val slowest = curve.maxOf { it.p99().inWholeNanoseconds }.takeIf { it > 0L } ?: return emptyList()

    val plotted = curve.map { rung ->
        val x = rung.rate.perSecond / fastest * WIDTH
        val y = HEADROOM + (1 - rung.p99().inWholeNanoseconds.toDouble() / slowest) * (PLOT - HEADROOM)
        rung to (x to y)
    }

    return listOf(
        """  <figure class="chart curve">""",
        """    <figcaption>response time p99 at each rate — up to ${curve.maxOf { it.p99() }.forReport()} """ +
            "at ${fastest.asRate()}</figcaption>",
        """    <svg viewBox="0 0 $WIDTH $HEIGHT" role="img" aria-label="the rate curve">""",
        """      <polyline class="curve-line" points="${plotted.joinToString(" ") { (_, at) ->
            "${at.first.round()},${at.second.round()}"
        }}"></polyline>""",
    ) + plotted.flatMap { (rung, at) -> dot(rung, at) } + chosenLines(fastest) + listOf(
        """      <text class="tick-label curve-start" x="0" y="$HEIGHT">0</text>""",
        """      <text class="tick-label curve-end" x="$WIDTH" y="$HEIGHT">${fastest.asRate()}</text>""",
        "    </svg>",
        "  </figure>",
    )
}

private fun dot(rung: Rung, at: Pair<Double, Double>): List<String> =
    listOf(
        """      <circle class="dot ${rung.outcome.word()}" cx="${at.first.round()}" """ +
            """cy="${at.second.round()}" r="3.5">""",
        "        <title>${rung.rate.perSecond.asRate()} — ${rung.p99().forReport()}, " +
            "${rung.outcome.word()}</title>",
        "      </circle>",
    )

private fun Capacity.chosenLines(fastest: Double): List<String> {
    val at = rate?.takeUnless { voided }?.perSecond?.div(fastest)?.times(WIDTH) ?: return emptyList()
    return listOf(
        """      <line class="chosen-mark" x1="${at.round()}" y1="0" x2="${at.round()}" y2="$PLOT"></line>""",
        """      <text class="chosen-label" x="${(at + LABEL_GAP).round()}" y="$LABEL_BASELINE">""" +
            "sustains ${rate?.perSecond?.asRate()}</text>",
    )
}

private fun Capacity.tableLines(): List<String> =
    if (curve.isEmpty()) emptyList()
    else listOf(
        """  <section class="steps" aria-label="Every rung">""",
        """    <div class="table-scroll">""",
        "      <table>",
        "        <thead>",
        "          <tr>",
    ) + COLUMNS.map { (heading, numeric) ->
        """            <th scope="col"${if (numeric) """ class="num"""" else ""}>$heading</th>"""
    } + listOf(
        "          </tr>",
        "        </thead>",
        "        <tbody>",
    ) + curve.flatMap { it.rowLines(chosen = it.rate == rate && !voided) } + listOf(
        "        </tbody>",
        "      </table>",
        "    </div>",
        "  </section>",
    )

private fun Rung.rowLines(chosen: Boolean): List<String> {
    val marked = if (chosen) """ <span class="chosen-tag">the operating point</span>""" else ""
    return listOf(
        """          <tr class="rung ${outcome.word()}${if (chosen) " chosen" else ""}" """ +
            """data-rate="${rate.perSecond}">""",
        """            <th scope="row">${rate.perSecond.asRate()}</th>""",
        """            <td class="num">${offered.perSecond.asRate()}</td>""",
        """            <td class="outcome">${outcome.word()}$marked</td>""",
        """            <td class="num">${result.count.grouped()}</td>""",
        """            <td class="num failed">${result.failed.grouped()}</td>""",
        """            <td class="num">${p99().forReport()}</td>""",
        """            <td class="goals">${judgement()}</td>""",
        "          </tr>",
    )
}

/** What the rung was judged to be, in the words the goals themselves use. */
private fun Rung.judgement(): String = when (outcome) {
    Rung.Outcome.Void -> "not judged — the injector lost the schedule"

    Rung.Outcome.Passed -> "every goal met"

    Rung.Outcome.Failed ->
        verdicts.filterNot { it.met }.joinToString(separator = "; ") { it.goal.described.escapedForHtml() }
}

private fun Capacity.noteLines(): List<String> =
    listOf(
        """  <p class="note">Each rung held its rate for the whole window and was judged on all of it; """ +
            "there is no warm-up. Response time counts from the departure the profile promised, so it " +
            "carries any backlog of the generator's own. A rung is void rather than failed when the " +
            "injector's own lateness passed one whole departure interval at p99 — a departure behind at " +
            "the tail — because the load it asked for was never offered, so nothing there is the " +
            "target's. Void rungs are still drawn: the rate the generator itself ran out at is worth " +
            "seeing. Offered is what left, against the rate the rung asked for.</p>",
        "</main>",
        "<script>",
    ) + THEME_JS.lines() + listOf(
        "</script>",
        "</body>",
        "</html>",
    )

private fun Rung.Outcome.word(): String = when (this) {
    Rung.Outcome.Passed -> "passed"
    Rung.Outcome.Failed -> "failed"
    Rung.Outcome.Void -> "void"
}

/** The slowest thing the rung measured, which is what a goal is judged against. */
private fun Rung.p99(): Duration = result.steps.values.maxOfOrNull { it.responseTime.p99 } ?: Duration.ZERO

private fun String.plural(count: Int): String = if (count == 1) this else "${this}s"

private fun Double.round(): String = String.format(Locale.ROOT, "%.1f", this)

/** Each column, and whether it holds a number. */
private val COLUMNS = listOf(
    "Rate" to false,
    "Offered" to true,
    "Verdict" to false,
    "Requests" to true,
    "Failed" to true,
    "p99" to true,
    "Goals" to false,
)

private const val WIDTH = 640.0
private const val PLOT = 120.0
private const val HEIGHT = 140
private const val HEADROOM = 8.0
private const val LABEL_GAP = 4.0
private const val LABEL_BASELINE = 12
