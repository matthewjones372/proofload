package io.github.matthewjones372.kestrel.report

import io.github.matthewjones372.kestrel.Measurement
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.Verdict
import java.util.Locale

/**
 * Whether the run was any good, by the only definition available: the one the
 * simulation stated. A run with no goals gets no verdict, because a tool that
 * invents a threshold is telling the same lie as an interpolated percentile.
 */
internal fun RunResult.verdictLines(): List<String> {
    val judged = verdicts
    if (judged.isEmpty()) return emptyList()

    val met = judged.count { it.met }
    val outcome = if (met == judged.size) "met" else "missed"

    return listOf(
        """  <section class="verdicts $outcome" aria-label="Goals">""",
        """    <p class="verdict-headline"><strong>$met of ${judged.size} """ +
            """${"goal".plural(judged.size)} met.</strong></p>""",
        """    <ul class="verdict-list">""",
    ) + judged.map { it.line() } + listOf("    </ul>", "  </section>")
}

private fun Verdict.line(): String {
    val margin = overBy?.let { " — <strong>${it.rounded()}% over</strong>" }.orEmpty()
    val mark = if (met) "met" else "missed"
    return """      <li class="$mark"><span class="goal">${goal.described.escapedForHtml()}</span>""" +
        """<span class="measured">${measured.shown()}$margin</span></li>"""
}

// Formatted here rather than in core, so a duration on a verdict reads exactly
// like the same duration in the table above it.
private fun Measurement.shown(): String = when (this) {
    is Measurement.Took -> duration.forReport()
    is Measurement.Share -> "${percent.rounded(1)}%"
    is Measurement.Absent -> because.escapedForHtml()
}

private fun Double.rounded(places: Int): String = String.format(Locale.ROOT, "%.${places}f", this)

private fun Double.rounded(): String = String.format(Locale.ROOT, "%.0f", this)

private fun String.plural(count: Int): String = if (count == 1) this else "${this}s"
