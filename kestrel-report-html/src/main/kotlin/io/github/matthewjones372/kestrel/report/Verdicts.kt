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

    // A refused verdict is neither: counting it as met would put a tick on a
    // number nothing could resolve, and counting it as missed would put a red
    // one on a goal nobody can chase.
    val met = judged.count { it.met && it.refused == null }
    val unresolved = judged.count { it.refused != null }
    val outcome = if (met + unresolved == judged.size) "met" else "missed"
    val caption = "${"goal".plural(judged.size)} met" +
        if (unresolved == 0) "" else ", $unresolved could not be told at this resolution"

    return listOf(
        """  <section class="verdicts $outcome" aria-label="Goals">""",
        """    <p class="verdict-headline">""",
        """      <span class="verdict-score">$met/${judged.size}</span>""",
        """      <span class="verdict-caption">$caption</span>""",
        "    </p>",
        """    <ul class="verdict-list">""",
    ) + judged.map { it.line() } + listOf("    </ul>", "  </section>")
}

private fun Verdict.line(): String {
    val margin = overBy?.let { " — <strong>${it.rounded()}% over</strong>" }.orEmpty()
    // A refused verdict counts as met so it cannot fail a build on a number
    // nothing could resolve, and is marked apart so nobody reads it as a tick
    // the run earned.
    val mark = when {
        refused != null -> "unresolved"
        met -> "met"
        else -> "missed"
    }
    val named = stage?.let { " <span class=\"stage\">stage ${it.index + 1} of ${it.of}</span>" }.orEmpty()
    val shown = refused?.let { "cannot tell — ${it.why.escapedForHtml()}" } ?: "${measured.shown()}$margin"
    return """      <li class="$mark"><span class="goal">${goal.described.escapedForHtml()}</span>$named""" +
        """<span class="measured">$shown</span></li>"""
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
