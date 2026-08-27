package io.github.matthewjones372.kestrel.report

import io.github.matthewjones372.kestrel.Difference
import io.github.matthewjones372.kestrel.Spread
import io.github.matthewjones372.kestrel.Tell
import kotlin.math.abs

/**
 * One statistic against the same statistic of the runs before, several runs a
 * side: how far it moved, the interval around that, and what the runs will
 * support anyone concluding.
 *
 * Where they support nothing the line says what would change that. A refusal a
 * reader cannot act on is one they learn to skip.
 */
internal fun List<Difference>.differenceLines(): List<String> =
    if (isEmpty()) {
        emptyList()
    } else {
        listOf(
            """  <section class="changes" aria-label="Against the runs before">""",
            """    <p class="change-headline"><strong>${headline()}</strong></p>""",
        ) + listOfNotNull(caveatLine()) + map { "    <p>${it.line()}</p>" } + listOf(
            """    <p class="note">Each interval is ten thousand resamples of the runs themselves, seeded """ +
                "so that reading one set of results twice reaches one verdict. A verdict is that interval " +
                "against the size a team declared worth acting on, rather than against no change at all.</p>",
            "  </section>",
        )
    }

private fun List<Difference>.headline(): String {
    val told = count { it.verdict is Tell.Better || it.verdict is Tell.Worse }
    return if (told == 0) {
        "Nothing here moved by more than these runs can tell."
    } else {
        "$told of $size ${if (size == 1) "statistic" else "statistics"} moved by more than these runs can tell."
    }
}

private fun List<Difference>.caveatLine(): String? =
    firstNotNullOfOrNull { it.caveat }
        ?.let { """    <p class="caveat" role="status">${it.escapedForHtml()}</p>""" }

private fun Difference.line(): String = when (val told = verdict) {
    Tell.Worse -> "${moved()}. <strong>Worse</strong> than ${acceptableInWords()}."

    Tell.Better -> "${moved()}. <strong>Better</strong> than ${acceptableInWords()}."

    // The interval is absent where the runs could not produce one at all, and
    // a size quoted without it would read as the finding rather than the input.
    is Tell.CannotTell -> if (interval == null) {
        "${statistic.described.escapedForHtml()}: <strong>cannot tell</strong>. ${told.said()}"
    } else {
        "${moved()}. <strong>Cannot tell</strong>: ${told.said()}"
    }
}

private fun Tell.CannotTell.said(): String =
    "${why.escapedForHtml()}. What would change it: ${wouldChangeIt.escapedForHtml()}."

private fun Difference.moved(): String {
    val direction = if (ratio >= 1.0) statistic.moreIs else statistic.lessIs
    return "${statistic.described.escapedForHtml()} is <strong>${abs(ratio - 1.0).asPercent()} " +
        "${direction.escapedForHtml()}</strong> (${interval.inWords()}$runs runs against $baselineRuns)"
}

private fun Spread?.inWords(): String =
    if (this == null) "" else "${(low - 1.0).asPercent()} to ${(high - 1.0).asPercent()}, "

private fun Difference.acceptableInWords(): String =
    if (acceptable.percent <= 0.0) "no change at all" else "the ${acceptable.described} that was declared acceptable"
