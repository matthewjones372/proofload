package io.github.matthewjones372.proofload.report

import io.github.matthewjones372.proofload.Band
import io.github.matthewjones372.proofload.Statistic
import io.github.matthewjones372.proofload.Tell
import io.github.matthewjones372.proofload.Trend
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.math.abs

/**
 * A series of points as one self-contained page: what each one read, what its
 * own runs can claim about that, and which adjacent pairs moved by more than
 * those runs can tell.
 *
 * The series is the point. A pairwise comparison is blind to a creep by
 * construction — every step of it sits inside the interval — so a page that
 * showed one pair would be the thing this exists to get past.
 */
public fun Trend.toHtmlReport(): String =
    documentLines().joinToString(separator = "\n", postfix = "\n")

/** Writes [toHtmlReport] to [path], creating the directories above it, and returns the path written. */
public fun Trend.writeHtmlReport(path: Path): Path {
    path.parent?.let { Files.createDirectories(it) }
    return announce(Files.writeString(path, toHtmlReport(), Charsets.UTF_8))
}

private fun Trend.documentLines(): List<String> =
    listOf(headLines(), endsLines(), seriesChartLines(), tableLines(), noteLines()).flatten()

private fun Trend.headLines(): List<String> =
    listOf(
        "<!doctype html>",
        """<html lang="en">""",
        "<head>",
        """<meta charset="utf-8">""",
        """<meta name="viewport" content="width=device-width, initial-scale=1">""",
        "<title>Proofload trend — ${statistic.described.escapedForHtml()}</title>",
        "<style>",
    ) + REPORT_CSS.lines() + listOf(
        "</style>",
        "</head>",
        "<body>",
        "<main>",
        """  <header class="run">""",
        """    <div class="run-name">""",
        """      <span class="wordmark">Proofload</span>""",
        "      <h1>What this has been doing</h1>",
        "    </div>",
        """    <div class="run-meta">""",
        """      <p class="when">${statistic.described.escapedForHtml()} over ${points.size} points, """ +
            "${points.first().label.escapedForHtml()} to ${points.last().label.escapedForHtml()}</p>",
        THEME_TOGGLE,
        "    </div>",
        "  </header>",
    )

/**
 * The headline, which is the oldest point against the newest.
 *
 * The one comparison a creep shows in: each adjacent step of a two-percent
 * drift is inside its own interval, and forty of them are not.
 */
private fun Trend.endsLines(): List<String> {
    val ends = this.ends
    val moved = when (val told = ends.verdict) {
        Tell.Worse, Tell.Better -> {
            val direction = if (ends.ratio >= 1.0) statistic.moreIs else statistic.lessIs
            "<strong>${abs(ends.ratio - 1.0).asPercent()} ${direction.escapedForHtml()}</strong> " +
                "end to end${ends.interval?.let { " (${(it.low - 1.0).asPercent()} to ${(it.high - 1.0).asPercent()})" }
                    .orEmpty()}"
        }

        is Tell.CannotTell ->
            "<strong>no move these points can tell</strong> end to end — " +
                told.why.escapedForHtml()
    }
    return listOf(
        """  <section class="operating" aria-label="Oldest against newest">""",
        """    <p class="operating-rate">$moved</p>""",
    ) + listOfNotNull(
        ends.caveat?.let { """    <p class="caveat" role="status">${it.escapedForHtml()}</p>""" },
    ) + listOf("  </section>")
}

/**
 * The reading at each point with the band its own runs support, one polyline
 * per segment.
 *
 * Nothing is drawn across a machine change and nothing is drawn between points
 * nobody ran: a line through a gap is the interpolation this tool refuses
 * everywhere else.
 */
private fun Trend.seriesChartLines(): List<String> {
    val readings = points.map { it.reading(statistic) ?: 0.0 }
    val bands = points.map { it.band(statistic) }
    val tallest = (readings + bands.mapNotNull { it?.high }).max().takeIf { it > 0.0 } ?: return emptyList()
    val at = { index: Int -> if (points.size == 1) 0.0 else index.toDouble() / (points.size - 1) * WIDTH }
    val height = { value: Double -> HEADROOM + (1 - value / tallest) * (PLOT - HEADROOM) }

    var drawn = 0
    val lines = segments.map { segment ->
        val from = drawn
        drawn += segment.size
        (from until drawn).joinToString(" ") { "${at(it).round()},${height(readings[it]).round()}" }
    }

    return listOf(
        """  <figure class="chart series-over-points">""",
        """    <figcaption>${statistic.described.escapedForHtml()} at each point, with what each """ +
            "point's own runs support</figcaption>",
        """    <svg viewBox="0 0 $WIDTH $HEIGHT" role="img" aria-label="the series">""",
    ) + lines.map { """      <polyline class="curve-line" points="$it"></polyline>""" } +
        points.indices.flatMap { index ->
            bandLines(bands[index], at(index), height) + listOf(
                """      <circle class="dot" cx="${at(index).round()}" """ +
                    """cy="${height(readings[index]).round()}" r="3.5">""",
                "        <title>${points[index].label.escapedForHtml()} — " +
                    "${readings[index].inUnits(statistic)}</title>",
                "      </circle>",
            )
        } + listOf(
            """      <text class="tick-label series-start" x="0" y="$HEIGHT">""" +
                "${points.first().label.escapedForHtml()}</text>",
            """      <text class="tick-label series-end" x="$WIDTH" y="$HEIGHT">""" +
                "${points.last().label.escapedForHtml()}</text>",
            "    </svg>",
            "  </figure>",
        )
}

private fun bandLines(band: Band?, x: Double, height: (Double) -> Double): List<String> =
    if (band == null) emptyList()
    else listOf(
        """      <line class="band" x1="${x.round()}" y1="${height(band.high).round()}" """ +
            """x2="${x.round()}" y2="${height(band.low).round()}"></line>""",
    )

private fun Trend.tableLines(): List<String> =
    listOf(
        """  <section class="steps" aria-label="Every point">""",
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
    ) + points.flatMap { rowLines(it) } + listOf(
        "        </tbody>",
        "      </table>",
        "    </div>",
        "  </section>",
    )

private fun Trend.rowLines(point: Trend.Point): List<String> {
    val pair = pairs.firstOrNull { it.to == point }
    val step = steps.firstOrNull { it.to == point }
    val broke = pair == null && point != points.first()
    return listOf(
        """          <tr class="point${if (step != null) " moved" else ""}""" +
            """" data-point="${point.label.escapedForHtml()}">""",
        """            <th scope="row">${point.label.escapedForHtml()}</th>""",
        """            <td class="num">${(point.reading(statistic) ?: 0.0).inUnits(statistic)}</td>""",
        """            <td class="num">${point.band(statistic)?.inUnits(statistic) ?: "—"}</td>""",
        """            <td class="num">${point.runs.size}</td>""",
        """            <td>${point.machine.toString().escapedForHtml()}</td>""",
        """            <td class="num">${point.probe?.took?.forReport() ?: "—"}</td>""",
        """            <td class="goals">${moved(pair, broke)}</td>""",
        "          </tr>",
    )
}

/**
 * What happened between the point before and this one.
 *
 * A machine change is said rather than judged: `Runs` refuses to merge unlike
 * machines, and a pair that straddles one moved by an amount nothing here can
 * separate from the runner.
 */
private fun Trend.moved(pair: Trend.Step?, broke: Boolean): String {
    if (broke) return "the runner changed here, so this pair is a break rather than a comparison"
    val difference = pair?.difference ?: return ""
    val direction = if (difference.ratio >= 1.0) statistic.moreIs else statistic.lessIs
    val size = "${abs(difference.ratio - 1.0).asPercent()} ${direction.escapedForHtml()}"
    // A cell left blank would read as "nothing happened" where what happened
    // is that a comparison was made and could not resolve it — which is the
    // whole reason the series is worth drawing.
    return when (difference.verdict) {
        is Tell.CannotTell -> "$size, which these runs cannot tell from no move"
        else -> "<strong>$size</strong> than ${pair.from.label.escapedForHtml()}"
    }
}

private fun Trend.noteLines(): List<String> =
    listOf(
        """  <p class="note">Each point is a set of runs, and its band is ten thousand resamples of """ +
            "those runs — what that point on its own can claim, not the range the next one will land in. " +
            "The headline is the oldest point against the newest, which is the one comparison a slow " +
            "creep shows in at all: a drift small enough to sit inside every adjacent interval still " +
            "moves the ends. " +
            "<strong>$comparisons</strong> adjacent ${"comparison".plural(comparisons)} " +
            "${if (comparisons == 1) "was" else "were"} made at 95%, so a series that never moved would " +
            "be expected to name about <strong>${stepsExpectedFromNoise.oneFigure()}</strong> of them " +
            "anyway; the ${steps.size} named here ${if (steps.size == 1) "is a place" else "are places"} " +
            "to look, not findings. No line is drawn between " +
            "points nobody ran, and none across a change of machine: a series that changed runners is " +
            "broken there rather than smoothed over. Nothing is fitted, so there is no slope here to " +
            "quote.</p>",
        "</main>",
        "<script>",
    ) + THEME_JS.lines() + listOf(
        "</script>",
        "</body>",
        "</html>",
    )

/** A reading in whatever the statistic is read in: a duration where it is one, a share where it is not. */
private fun Double.inUnits(statistic: Statistic): String = statistic.magnitudeOf(this)?.forReport() ?: asPercent()

private fun Band.inUnits(statistic: Statistic): String =
    "${low.inUnits(statistic)} to ${high.inUnits(statistic)}"

private fun String.plural(count: Int): String = if (count == 1) this else "${this}s"

private fun Double.oneFigure(): String = String.format(Locale.ROOT, "%.1f", this)

private fun Double.round(): String = String.format(Locale.ROOT, "%.1f", this)

private val COLUMNS = listOf(
    "Point" to false,
    "Reading" to true,
    "What its runs support" to true,
    "Runs" to true,
    "Machine" to false,
    "Probe" to true,
    "Against the point before" to false,
)

private const val WIDTH = 640.0
private const val PLOT = 120.0
private const val HEIGHT = 140
private const val HEADROOM = 8.0
