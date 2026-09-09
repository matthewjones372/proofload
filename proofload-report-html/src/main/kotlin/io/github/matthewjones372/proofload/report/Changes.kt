package io.github.matthewjones372.proofload.report

import io.github.matthewjones372.proofload.Change
import io.github.matthewjones372.proofload.Comparison
import io.github.matthewjones372.proofload.Floor

/**
 * This run against the last one.
 *
 * "Not distinguishable" rather than a percentage is the point: a p99 over a few
 * hundred samples moves between identical runs, and a team that acts on that
 * learns to ignore the report.
 *
 * A refusal is printed rather than left off. A page with no comparison on it
 * reads the same whether this was the first run or the cache key broke.
 */
internal fun Comparison?.comparisonLines(floor: Floor?): List<String> =
    if (floor != null && !floor.supportsAClaim) floor.refusalLines() else comparisonLines()

/**
 * Where the comparison would have gone, on a machine that moves by
 * [Floor.UNUSABLE] of itself between identical runs. Such a machine cannot
 * separate a regression from its own weather, and a delta printed under that is
 * one somebody goes and acts on.
 */
private fun Floor.refusalLines(): List<String> = section(
    headline = "This machine cannot support a latency claim.",
    notes = listOf(
        "Repeats of one unchanging measurement landed ${resolution.asPercent()} apart here, so nothing " +
            "smaller than that is the code rather than the machine. Nothing is compared.",
    ),
)

private fun Comparison?.comparisonLines(): List<String> = when (this) {
    null -> emptyList()

    is Comparison.NotComparable -> section(headline = "Not compared to the last run.", notes = listOf(why))

    is Comparison.Compared ->
        if (changes.isEmpty()) {
            emptyList()
        } else {
            section(
                headline = headline(),
                warning = caveat,
                rows = changes.map { it.line() },
                notes = listOf(
                    "Compared at p99 of response time. A sampling interval bounds which sample the p99 landed " +
                        "on, given how many there were. It does not bound how far a repeat of this run would " +
                        "land from it, because two runs of one unchanged target drift by the machine as well " +
                        "as by the code. So this says these samples differ, not that the target did — " +
                        "repeated runs are what answers the second.",
                ),
            )
        }
}

private fun section(
    headline: String,
    warning: String? = null,
    rows: List<String> = emptyList(),
    notes: List<String> = emptyList(),
): List<String> =
    listOf(
        """  <section class="changes" aria-label="Against the last run">""",
        """    <p class="change-headline"><strong>${headline.escapedForHtml()}</strong></p>""",
    ) + listOfNotNull(warning?.let { """    <p class="caveat" role="status">${it.escapedForHtml()}</p>""" }) +
        rows.wrappedInList() +
        notes.map { """    <p class="note">${it.escapedForHtml()}</p>""" } +
        listOf("  </section>")

private fun List<String>.wrappedInList(): List<String> =
    if (isEmpty()) emptyList() else listOf("""    <ul class="change-list">""") + this + listOf("    </ul>")

private fun Comparison.Compared.headline(): String {
    val moved = changes.count { it is Change.Worse || it is Change.Better }
    return if (moved == 0) {
        "Nothing measurably changed since the last run."
    } else {
        "$moved of ${changes.size} ${"step".plural(changes.size)} measurably changed since the last run."
    }
}

private fun Change.line(): String = when (this) {
    is Change.Indistinguishable -> row(
        "same",
        "${now.forReport()}, was ${before.forReport()} — <strong>not distinguishable</strong>",
    )

    is Change.Worse -> row(
        "worse",
        "${now.forReport()} (${interval.low.forReport()}–${interval.high.forReport()}), " +
            "was ${before.forReport()} — <strong>worse</strong>",
    )

    is Change.Better -> row(
        "better",
        "${now.forReport()} (${interval.low.forReport()}–${interval.high.forReport()}), " +
            "was ${before.forReport()} — <strong>better</strong>",
    )

    is Change.Added -> row("new", "<strong>did not run last time</strong>")

    is Change.Gone -> row("gone", "<strong>ran last time and did not run now</strong>")
}

private fun Change.row(mark: String, detail: String): String =
    """      <li class="$mark"><span class="goal">${step.escapedForHtml()}</span>""" +
        """<span class="measured">$detail</span></li>"""

private fun String.plural(count: Int): String = if (count == 1) this else "${this}s"
