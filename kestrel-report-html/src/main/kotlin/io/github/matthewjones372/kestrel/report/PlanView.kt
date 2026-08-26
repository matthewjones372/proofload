package io.github.matthewjones372.kestrel.report

import io.github.matthewjones372.kestrel.Arrivals
import io.github.matthewjones372.kestrel.InjectionProfile
import io.github.matthewjones372.kestrel.Plan
import io.github.matthewjones372.kestrel.seeds
import java.util.Locale
import kotlin.time.Duration

/** The run as it was asked for: the scenario, the shape, and what that adds up to. */
internal fun Plan.headerLines(arrivals: Arrivals): List<String> {
    val shape = profile ?: return emptyList()

    return listOf(
        """  <section class="plan" aria-label="What was asked for">""",
        """    <p><strong>${scenario.escapedForHtml()}</strong> — ${steps.size} ${"step".plural(steps.size)}, """ +
            "${shape.described()}. Planned ${plannedUsers.grouped()} " +
            "${"user".plural(plannedUsers)}, ${plannedRequests.grouped()} " +
            "${"request".plural(plannedRequests)}.</p>",
        """    <p class="arrivals">${shape.arrivalProcess()}${arrivals.achieved()}</p>""",
    ) + shape.shapeChart() + listOf("  </section>")
}

/**
 * A line rather than a warning. Even arrivals are not wrong, they are a choice
 * whose consequence — a p99 that is optimistic against the same mean rate in
 * production — is invisible unless the page names which was asked for.
 */
private fun InjectionProfile.arrivalProcess(): String =
    seeds.takeIf { it.isNotEmpty() }
        ?.let { drawn -> "Arrivals were drawn from ${"seed".plural(drawn.size)} ${drawn.joinToString(", ")}." }
        ?: "Arrivals were evenly spaced, which understates queueing against the same mean rate in production."

/** Measured from the departures that went out, so the claim above it has a number under it. */
private fun Arrivals.achieved(): String =
    if (count < 2L) ""
    else " Measured ${mean.forReport()} between departures, coefficient of variation " +
        "${String.format(Locale.ROOT, "%.2f", cov)}."

/** The shape in words, one clause per stage, in the order they run. */
private fun InjectionProfile.described(): String = when (this) {
    is InjectionProfile.ConstantRate -> "${perSecond.rate()} held for ${over.forPlan()}"
    is InjectionProfile.RampRate -> "${from.rate()} to ${to.rate()} over ${over.forPlan()}"
    is InjectionProfile.Stages -> stages.joinToString(separator = ", then ") { it.described() }
    is InjectionProfile.Randomized -> of.described()
}

private fun Double.rate(): String = "${String.format(Locale.ROOT, "%,.6g", this).trimNumber()}/s"

private fun String.trimNumber(): String =
    if (contains('.')) trimEnd('0').trimEnd('.') else this

/**
 * The intent, drawn: rate against time, flat for a hold and sloped for a ramp.
 *
 * From the profile rather than from recorded traffic on purpose. It is what was
 * asked for, and putting it beside what happened is what makes a shortfall
 * something a reader sees rather than works out.
 */
private fun InjectionProfile.shapeChart(): List<String> {
    val points = corners()
    val peak = points.maxOf { it.second }.takeIf { it > 0.0 } ?: return emptyList()
    val total = over.inWholeNanoseconds.toDouble().takeIf { it > 0.0 } ?: return emptyList()

    // A little headroom, so a flat hold is a band rather than a line pinned to
    // the top edge of its own box.
    val plotted = points.map { (at, rate) ->
        val x = at.inWholeNanoseconds / total * WIDTH
        val y = HEADROOM + (1 - rate / peak) * (PLOT - HEADROOM)
        x to y
    }
    val path = plotted.joinToString(" ") { (x, y) -> "${x.round()},${y.round()}" }
    val area = "0.0,${PLOT.round()} $path ${WIDTH.round()},${PLOT.round()}"

    return listOf(
        """    <figure class="chart shape">""",
        """      <figcaption>the shape that was asked for — peak ${peak.rate()}</figcaption>""",
        """      <svg viewBox="0 0 $WIDTH $HEIGHT" role="img" preserveAspectRatio="none" aria-label="load shape">""",
        """        <polygon class="shape-area" points="$area"></polygon>""",
        """        <polyline class="shape-line" points="$path"></polyline>""",
        """        <text class="tick-label shape-start" x="0" y="$HEIGHT">0</text>""",
        """        <text class="tick-label shape-end" x="$WIDTH" y="$HEIGHT">${over.forPlan()}</text>""",
        "      </svg>",
        "    </figure>",
    )
}

/** Where the rate line changes direction: the start and end of every stage. */
private fun InjectionProfile.corners(from: Duration = Duration.ZERO): List<Pair<Duration, Double>> = when (this) {
    is InjectionProfile.ConstantRate -> listOf(from to perSecond, (from + over) to perSecond)

    is InjectionProfile.RampRate -> listOf(from to this.from, (from + over) to to)

    is InjectionProfile.Stages ->
        stages
            .runningFold(from to emptyList<Pair<Duration, Double>>()) { (at, _), stage ->
                (at + stage.over) to stage.corners(at)
            }
            .flatMap { (_, corners) -> corners }

    // The rate line is the shape underneath; randomising moves arrivals along
    // it without moving it.
    is InjectionProfile.Randomized -> of.corners(from)
}

private fun String.plural(count: Int): String = plural(count.toLong())

private fun String.plural(count: Long): String = if (count == 1L) this else "${this}s"

private fun Double.round(): String = String.format(Locale.ROOT, "%.1f", this)

private const val WIDTH = 640.0
private const val PLOT = 60.0
private const val HEADROOM = 8.0
private const val HEIGHT = 78
