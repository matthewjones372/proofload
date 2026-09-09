package io.github.matthewjones372.proofload.report

import io.github.matthewjones372.proofload.RunResult
import io.github.matthewjones372.proofload.StepStats

/**
 * What the failed requests took, beside what the successful ones took.
 *
 * Absent when nothing failed. A panel of zeroes on every clean run is one
 * readers learn to skip, and this is the panel that says a good-looking p99 was
 * made of rejections.
 */
internal fun RunResult.failedLines(): List<String> {
    val failing = steps.values.filter { it.failed.count > 0L }
    if (failing.isEmpty()) return emptyList()

    return listOf(
        """  <section class="failures" aria-label="What the failures took">""",
        "    <h2>What failed, and how fast</h2>",
        """    <ul class="failures-list">""",
    ) + failing.map { it.failedLine() } + listOf(
        "    </ul>",
        """    <p class="note">Service time, so this is the target's own speed rather than a wait this tool """ +
            "added. The table below counts these samples alongside the successes, and a target that sheds " +
            "load answers a rejection at once — the faster it sheds, the better that table looks. A step " +
            "that failed nothing is not listed.</p>",
        "  </section>",
    )
}

private fun StepStats.failedLine(): String {
    val against = if (ok.count == 0L) {
        "and nothing succeeded to compare it against"
    } else {
        "against ${ok.serviceTime.p99.forReport()} for the ${ok.count.grouped()} that worked"
    }
    return """      <li><span class="failures-step">${name.escapedForHtml()}</span>""" +
        """<span class="failures-value">${failed.count.grouped()} failed at """ +
        "${failed.serviceTime.p99.forReport()} p99, $against</span></li>"
}
