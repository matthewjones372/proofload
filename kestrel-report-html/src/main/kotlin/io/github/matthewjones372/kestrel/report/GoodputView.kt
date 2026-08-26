package io.github.matthewjones372.kestrel.report

import io.github.matthewjones372.kestrel.Goal
import io.github.matthewjones372.kestrel.Met
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.StepStats
import io.github.matthewjones372.kestrel.goodput
import io.github.matthewjones372.kestrel.met
import java.util.Locale
import kotlin.time.Duration

/**
 * The share of requests that met the target and the rate that share amounts to:
 * the share is the promise, the rate is the capacity.
 *
 * Only against targets the plan declared, for the reason a run with no goals
 * gets no verdict: a page that chose its own target would be inventing the
 * promise it is reporting against.
 */
internal fun RunResult.goodputLines(): List<String> {
    val goals = plan.goals.filterIsInstance<Goal.GoodputAtLeast>()
    if (goals.isEmpty()) return emptyList()

    return listOf(
        """  <section class="goodput" aria-label="Goodput">""",
        "    <h2>Goodput</h2>",
    ) + goals.map { it.under }.distinct().map { headline(it) } + listOf(
        """    <ul class="goodput-list">""",
    ) + goals.map { goodputLine(it) } + listOf(
        "    </ul>",
        """    <p class="note">Goodput counts the requests that both succeeded and came back inside the """ +
            "target, over the window the plan asked for rather than the span the run took. The bucket " +
            "holding the target counts as having missed it, and a request that missed it is charged " +
            "against the successes — so each number here is the lowest the counts allow rather than the " +
            "likeliest.</p>",
        "  </section>",
    )
}

private fun RunResult.headline(under: Duration): String {
    val rate = goodput(under)
        ?: return """    <p class="goodput-headline none">Nothing was planned for this run, so there is no """ +
            "window to report a rate over.</p>"

    return """    <p class="goodput-headline"><strong>${rate.perSecond.asRate()}</strong> under """ +
        "${under.forReport()} across the run, over the ${plan.plannedWindow.forPlan()} the plan asked " +
        "for.</p>"
}

private fun RunResult.goodputLine(goal: Goal.GoodputAtLeast): String {
    val step = goal.step.name
    val shown = steps[step]?.let { measured(it, goal) }
        ?: """<span class="goodput-value none">Not measured — the step never ran.</span>"""
    return """      <li><span class="goodput-step">${step.escapedForHtml()} under """ +
        "${goal.under.forReport()}</span>$shown</li>"
}

private fun RunResult.measured(stats: StepStats, goal: Goal.GoodputAtLeast): String =
    when (val met = stats.met(goal.under, goal.clock)) {
        is Met.Absent -> """<span class="goodput-value none">Not measured — ${met.because.escapedForHtml()}.</span>"""

        is Met.Measured -> """<span class="goodput-value">${(met.fraction * PERCENT).asShare()}""" +
            "${rateOf(stats, goal)}</span>"
    }

// The window is the plan's, and a result built from samples has none: the share
// is still a measurement without one, and the rate is not.
private fun RunResult.rateOf(stats: StepStats, goal: Goal.GoodputAtLeast): String =
    plan.plannedWindow.takeIf { it > Duration.ZERO }
        ?.let { " · ${stats.goodput(goal.under, over = it, of = goal.clock).perSecond.asRate()}" }
        .orEmpty()

private fun Double.asShare(): String = String.format(Locale.ROOT, "%.1f%%", this)

private const val PERCENT = 100.0
