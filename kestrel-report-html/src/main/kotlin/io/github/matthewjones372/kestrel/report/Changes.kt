package io.github.matthewjones372.kestrel.report

import io.github.matthewjones372.kestrel.Change

/**
 * This run against the last one.
 *
 * "Not distinguishable" rather than a percentage is the point: a p99 over a few
 * hundred samples moves between identical runs, and a team that acts on that
 * learns to ignore the report.
 */
internal fun List<Change>.comparisonLines(): List<String> {
    if (isEmpty()) return emptyList()

    val moved = count { it is Change.Worse || it is Change.Better }
    val headline = if (moved == 0) "Nothing measurably changed since the last run."
    else "$moved of $size ${"step".plural(size)} measurably changed since the last run."

    return listOf(
        """  <section class="changes" aria-label="Against the last run">""",
        """    <p class="change-headline"><strong>${headline.escapedForHtml()}</strong></p>""",
        """    <ul class="change-list">""",
    ) + map { it.line() } + listOf(
        "    </ul>",
        """    <p class="note">Compared at p99 of response time, with 95% sampling intervals. Two runs whose """ +
            "intervals overlap have not been shown to differ — the fix for that is a longer run, not a " +
            "closer look.</p>",
        "  </section>",
    )
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
