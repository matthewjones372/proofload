package io.github.matthewjones372.kestrel.report

import io.github.matthewjones372.kestrel.Change
import io.github.matthewjones372.kestrel.Comparison

/**
 * This run against the last one.
 *
 * "Not distinguishable" rather than a percentage is the point: a p99 over a few
 * hundred samples moves between identical runs, and a team that acts on that
 * learns to ignore the report.
 *
 * A refusal is printed rather than left off. A page with no comparison on it
 * reads the same whether this was the first run or the cache key broke.
 *
 * Whether the machine could support any of this is not asked here. It is a
 * question per claim rather than per machine — the movement that swamps a
 * hundred-microsecond step is invisible at a quarter of a second — so it is
 * answered beside each verdict, by the code that reaches one.
 */
internal fun Comparison?.comparisonLines(): List<String> = when (this) {
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
                    "Compared at p99 of response time, with 95% sampling intervals. Two runs whose intervals " +
                        "overlap have not been shown to differ — the fix for that is a longer run, not a " +
                        "closer look.",
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
