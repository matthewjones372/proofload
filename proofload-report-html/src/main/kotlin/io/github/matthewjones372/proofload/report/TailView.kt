package io.github.matthewjones372.proofload.report

import io.github.matthewjones372.proofload.RunResult
import io.github.matthewjones372.proofload.StepStats
import io.github.matthewjones372.proofload.Tail
import io.github.matthewjones372.proofload.Timing
import io.github.matthewjones372.proofload.interval

/**
 * The p99.9 of each step, with the range it could plausibly sit in.
 *
 * Off the table rather than another column in it, because the interval is the
 * half a reader needs: one request in a thousand is the thinnest number the
 * page prints, and without its width it reads as firmly as the p50.
 */
internal fun RunResult.tailLines(): List<String> {
    if (steps.isEmpty()) return emptyList()

    return listOf(
        """  <section class="tail" aria-label="The tail">""",
        "    <h2>p99.9</h2>",
        """    <ul class="tail-list">""",
    ) + steps.values.map { it.tailLine() } + listOf(
        "    </ul>",
        """    <p class="note">Response time, and the range beside it is the 95% sampling interval — the """ +
            "width a percentile resting on one request in a thousand has. It is where two JVM collectors " +
            "that match to p99 come apart. A step under " +
            "${Timing.SAMPLES_FOR_P999.grouped()} samples has not measured one request in a thousand, so it " +
            "says so rather than printing a percentile nobody reached.</p>",
        "  </section>",
    )
}

private fun StepStats.tailLine(): String {
    val shown = when (val tail = responseTime.p999) {
        is Tail.Measured -> """<span class="tail-value">${tail.duration.forReport()}${spread()}</span>"""
        is Tail.Absent -> """<span class="tail-value none">Not measured — ${tail.because.escapedForHtml()}.</span>"""
    }
    return """      <li><span class="tail-step">${name.escapedForHtml()}</span>$shown${exemplar()}</li>"""
}

/**
 * A trace id belonging to a request that landed here, where the run was traced.
 *
 * The question a tail provokes is not how slow it was but show me one, and an
 * id is the whole answer: a reader pastes it into whatever holds their traces.
 * Absent where nothing was traced rather than said to be missing — a line about
 * a feature the run did not use is noise on every run that did not use it.
 */
private fun StepStats.exemplar(): String {
    val trace = responseTime.exemplar(TAIL) ?: return ""
    return """ <span class="tail-trace">trace <code>${trace.escapedForHtml()}</code></span>"""
}

private fun StepStats.spread(): String {
    val interval = responseTime.interval(TAIL) ?: return ""
    return """ <span class="tail-interval">""" +
        "(${interval.low.forReport()}–${interval.high.forReport()})</span>"
}

private const val TAIL = 99.9
