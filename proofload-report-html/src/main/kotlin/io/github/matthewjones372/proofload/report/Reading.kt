package io.github.matthewjones372.proofload.report

import io.github.matthewjones372.proofload.RunResult
import io.github.matthewjones372.proofload.StepStats
import io.github.matthewjones372.proofload.fellBehind
import kotlin.math.ceil
import kotlin.math.pow

/**
 * What the numbers below support, in sentences.
 *
 * Every claim here is arithmetic over counts the run already has. The page is
 * still reporting only measurements; it is doing the sums a reader would
 * otherwise do by hand, which are the sums people get wrong.
 */
internal fun RunResult.readingLines(): List<String> {
    val sentences = listOfNotNull(
        totalSentence(),
        shortfallSentence(),
        scheduleSentence(),
        slowestSentence(),
        weightSentence(),
        journeySentence(),
    )
    if (sentences.isEmpty()) return emptyList()

    return listOf("""  <section class="reading" aria-label="What these numbers say">""") +
        sentences.map { """    <p>$it</p>""" } +
        listOf("  </section>")
}

private fun RunResult.totalSentence(): String {
    val share = if (count == 0L) 0.0 else failed.toDouble() / count * PERCENT
    val failedPart = if (failed ==
        0L
    ) "none failed" else "<strong>${failed.grouped()} failed</strong> (${share.oneDecimal()}%)"
    return "<strong>${count.grouped()} requests</strong>, $failedPart."
}

/**
 * What was sent against what was asked for. A run that sent three quarters of
 * the load is describing a lighter test than the one somebody wrote, and
 * without this the page looks identical to one that sent all of it.
 */
private fun RunResult.shortfallSentence(): String? {
    val planned = plan.plannedRequests
    if (planned == 0L) return null
    if (count >= planned) return "The run sent all ${planned.grouped()} of them."

    val missing = planned - count
    val share = missing.toDouble() / planned * PERCENT
    return "<strong>${missing.grouped()} of the ${planned.grouped()} planned requests never went out</strong> " +
        "(${share.oneDecimal()}%), so this page describes a lighter run than the one that was asked for. " +
        "Users abandoned after a failed step account for some of it; the rest is load that did not leave."
}

private fun RunResult.scheduleSentence(): String =
    if (fellBehind()) {
        "The generator fell behind its own schedule by ${behind.p99.forReport()} at p99, so the response " +
            "times below include time this tool spent queueing rather than time the target took."
    } else {
        "The run kept to its schedule, so these latencies are the target's rather than this tool's."
    }

private fun RunResult.slowestSentence(): String? {
    val slowest = steps.values.maxByOrNull { it.serviceTime.p99 } ?: return null
    val failures = if (slowest.failed.count == 0L) "" else
        ", and it carries ${slowest.failed.count.grouped()} of the ${failed.grouped()} failures"
    return "<strong>${slowest.name.escapedForHtml()} is the slowest step</strong> at " +
        "${slowest.serviceTime.p99.forReport()} p99$failures."
}

/**
 * How much weight a p99 will bear. A four-second run at 120 a second gives 480
 * samples, so its p99 rests on five requests and moves by tens of milliseconds
 * between runs — which is worth knowing before anyone quotes it in a meeting.
 */
private fun RunResult.weightSentence(): String? {
    val thinnest = steps.values.minByOrNull { it.count } ?: return null
    if (thinnest.count >= FIRM) return null

    val behindP99 = samplesBeyond(thinnest, P99)
    return "<strong>p99 rests on ${behindP99.requests()}</strong> for " +
        "${thinnest.name.escapedForHtml()}. Treat it as a hint rather than a number: a longer run is the " +
        "only thing that firms a percentile up."
}

/**
 * The percentile in the units a reader cares about. A user meeting a p99
 * somewhere in a journey of three steps is one in thirty-four, not one in a
 * hundred, and that arithmetic is where most misreadings start.
 */
private fun RunResult.journeySentence(): String? {
    val steps = steps.size
    if (steps < 2) return null

    val everyStepFast = (P99 / PERCENT).pow(steps)
    val oneIn = (1.0 / (1 - everyStepFast)).toLong()
    return "A user completes $steps steps, so about <strong>1 user in $oneIn</strong> meets a p99 somewhere " +
        "in the journey — assuming the steps are independent, which they are not quite. Percentiles do not " +
        "add: the journey's own p99 is not the sum of the steps'."
}

/** How many samples sit at or beyond a percentile — the weight behind it. */
internal fun samplesBeyond(step: StepStats, percentile: Double): Long =
    ceil(step.count * (1 - percentile / PERCENT)).toLong().coerceAtLeast(1L)

private fun Long.requests(): String = if (this == 1L) "1 request" else "${grouped()} requests"

private fun Double.oneDecimal(): String = ((this * TEN).toLong() / TEN).toString()

private const val PERCENT = 100.0
private const val P99 = 99.0
private const val TEN = 10.0

/** Below this many samples a percentile moves visibly between runs. */
private const val FIRM = 100L
