package io.github.matthewjones372.kestrel.report

import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.SteadyState
import io.github.matthewjones372.kestrel.steadyState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Which part of the run the goals above were judged over, and what was left
 * out of them.
 *
 * Absent under [SteadyState.LEAST_INTERVALS] seconds: a run that short has
 * nothing to detect, and a paragraph refusing to answer a question nobody
 * could ask is one readers learn to skip.
 */
internal fun RunResult.steadyLines(): List<String> {
    if (timeline.size < SteadyState.LEAST_INTERVALS) return emptyList()

    return when (val settled = steadyState) {
        is SteadyState.From -> settledLines(settled.offset)
        is SteadyState.NeverSettled -> neverSettledLines(settled.why)
    }
}

private fun RunResult.settledLines(offset: Duration): List<String> = listOf(
    """  <p class="note" id="kestrel-steady">""",
    "    <strong>Settled after ${offset.forPlan()}</strong>, judged over the remaining " +
        "${(timeline.size.seconds - offset).forPlan()}. The first ${offset.forPlan()} are drawn on the timeline " +
        "below and left out of every goal above that reads the counts or the target's service time; response " +
        "time is not kept second by second, so a goal on that is judged over the whole run. Every percentile in " +
        "the table below is the whole run: nothing here is discarded. ${aDefault()}",
    "  </p>",
)

private fun neverSettledLines(why: String): List<String> = listOf(
    """  <p class="behind" id="kestrel-steady" role="status">""",
    "    <strong>Never settled.</strong> ${why.escapedForHtml()}. Every goal was judged over the whole run, and " +
        "the percentiles below describe a target that was still moving. ${aDefault()}",
    "  </p>",
)

/**
 * The tolerance, printed wherever a verdict from it is. It is the one number
 * here nobody measured, and a reader who cannot see it cannot tell a settled
 * run from a generous threshold.
 */
private fun aDefault(): String =
    "Two windows count as the same within <strong>${SteadyState.TOLERANCE.asPercent()}</strong> of each other — " +
        "twice the width of the buckets these seconds are read from, and a default rather than something this " +
        "run discovered."
