package io.github.matthewjones372.kestrel.report

import io.github.matthewjones372.kestrel.Arrivals
import io.github.matthewjones372.kestrel.InjectionProfile
import io.github.matthewjones372.kestrel.Plan
import io.github.matthewjones372.kestrel.PlannedArm
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.ThinkTime
import io.github.matthewjones372.kestrel.seeds
import io.github.matthewjones372.kestrel.startRate
import java.util.Locale
import kotlin.time.Duration

/** The run as it was asked for: the arms, the shape of each, and what that adds up to. */
internal fun RunResult.planLines(): List<String> {
    if (plan.arms.all { it.profile == null }) return emptyList()

    return listOf(
        """  <section class="plan" aria-label="What was asked for">""",
        """    <p><strong>${plan.named()}</strong> — ${plan.steps.size} ${"step".plural(plan.steps.size)}, """ +
            "${plan.asked()}. Planned ${plan.plannedUsers.grouped()} " +
            "${"user".plural(plan.plannedUsers)}, ${plan.plannedRequests.grouped()} " +
            "${"request".plural(plan.plannedRequests)}.</p>",
        """    <p class="arrivals">${plan.arrivalProcess()}${arrivals.achieved()}${plan.warmed()}</p>""",
    ) + plan.thinkingLines() + listOf("""    <p class="note">${plan.rateMeans()}</p>""") +
        mixLines() + plan.shapeCharts() + listOf("  </section>")
}

/**
 * What was thrown away before the measurement, where a run declared one.
 *
 * Absent otherwise rather than "no warm-up": a page saying a run did not do
 * something it never asked to do is a line every reader learns to skip.
 */
private fun Plan.warmed(): String {
    val warmUp = warmUp ?: return ""
    val rate = arms.firstNotNullOfOrNull { it.profile }?.startRate?.perSecond?.asRate()
    val at = if (rate == null) "" else " at $rate"
    return " Warmed for ${warmUp.over.forPlan()}$at, not counted."
}

/**
 * What the users waited between steps, where they waited at all.
 *
 * Named rather than left to the reader for the reason the arrivals line is: a
 * constant pause is a choice whose consequence — every user that met it
 * leaving it in the same instant — is invisible unless the page says which was
 * asked for. Absent where a scenario has no pause at all.
 */
private fun Plan.thinkingLines(): List<String> {
    val waits = arms.flatMap { it.thinkTimes }.ifEmpty { return emptyList() }
    val drawn = arms.filter { arm -> arm.thinkTimes.any { it !is ThinkTime.Constant } }.mapNotNull { it.thinkSeed }
    val described = waits.distinct().joinToString(separator = ", ") { it.described() }
    val from = if (drawn.isEmpty()) {
        " Every user waited exactly that long, so users that arrive together click again together."
    } else {
        " Drawn from ${if (drawn.size == 1) "seed" else "seeds"} ${drawn.joinToString(", ")}."
    }
    return listOf("""    <p class="note">Think time: $described.$from</p>""")
}

private fun ThinkTime.described(): String = when (this) {
    is ThinkTime.Constant -> "a fixed ${duration.forPlan()}"
    is ThinkTime.Exponential -> "exponential, mean ${mean.forPlan()}"
    is ThinkTime.Lognormal -> "lognormal, median ${median.forPlan()}, sigma $sigma"
    is ThinkTime.Uniform -> "uniform, ${from.forPlan()} to ${until.forPlan()}"
}

/**
 * What the rate above counts, said in words rather than left to the reader.
 *
 * A profile's rate is users a second, never requests or messages a second: a
 * user goes on to make as many requests as its scenario has steps, and in a
 * stream test it opens one connection and receives many messages on it. A
 * reader who takes `2,000/s` on a page about a price feed for messages a
 * second has misread it by three orders of magnitude, and the page can prevent
 * that for the cost of a line.
 */
private fun Plan.rateMeans(): String =
    "The rate above is users a second — each one opens a connection or makes a request and then walks its " +
        "${steps.size} ${"step".plural(steps.size)}. What was recorded a second is on the timeline below."

/** Every arm's scenario: one is the run's name, and several are the mix that ran. */
private fun Plan.named(): String = arms.joinToString(separator = " + ") { it.scenario.escapedForHtml() }

/** What was asked for: one arm's shape, or how many arms and how long the longest of them runs. */
private fun Plan.asked(): String =
    arms.singleOrNull()?.profile?.described() ?: "${arms.size} arms over ${plannedWindow.forPlan()}"

/**
 * Each arm's share of the run: what the plan asked for, beside what was
 * counted. Absent for one arm, where both shares are the whole run and the
 * line would say nothing.
 */
private fun RunResult.mixLines(): List<String> {
    if (plan.arms.size < 2) return emptyList()

    val counted = plan.arms.map { usersCounted(it) }
    val measured = counted.sum()
    return listOf("""    <ul class="mix-list">""") +
        plan.arms.zip(counted) { arm, users -> armLine(arm, users, measured, plan.plannedUsers) } +
        listOf("    </ul>", """    <p class="note">${mixNote(measured)}</p>""")
}

private fun armLine(arm: PlannedArm, counted: Long, measured: Long, planned: Long): String =
    """      <li><span class="mix-arm">${arm.scenario.escapedForHtml()}</span>""" +
        """<span class="mix-shape">${arm.profile?.described() ?: "no shape"}</span>""" +
        """<span class="mix-share">${arm.plannedUsers.shareOf(planned)} asked, """ +
        "${counted.shareOf(measured)} departed</span></li>"

/**
 * What the two shares are, said where they are printed.
 *
 * The departed share is users counted rather than users departed: nothing
 * records a departure per arm, and the arms' own step counts are the only
 * split of the run there is.
 */
private fun mixNote(measured: Long): String =
    if (measured == 0L) {
        "<strong>Asked</strong> is the arm's share of the users the plan named. This run counted no users, so " +
            "what departed cannot be split by arm and only what was asked for is printed."
    } else {
        "<strong>Asked</strong> is the arm's share of the users the plan named. <strong>Departed</strong> is " +
            "its share of the ${measured.grouped()} users the run counted: the most any one step of the arm " +
            "was reached by. A user that failed a step still reached it, so this is exact for a scenario " +
            "whose steps every user meets, and a floor for one that puts its steps behind a condition."
    }

/**
 * The users the run counted in an arm.
 *
 * A user is counted once per step it reaches, so the most-reached step of an
 * arm is the users that arm saw. Abandonment does not lower it — a user that
 * failed a step still reached it — so this is exact wherever every user of an
 * arm meets at least one common step, which is every scenario that does not
 * open with a condition. Where one does, it is a floor.
 */
private fun RunResult.usersCounted(arm: PlannedArm): Long =
    arm.steps.mapNotNull { steps[it]?.reached }.maxOrNull() ?: 0L

private fun Long.shareOf(whole: Long): String =
    if (whole == 0L) NOTHING_MEASURED else (toDouble() / whole).asPercent()

/**
 * A line rather than a warning. Even arrivals are not wrong, they are a choice
 * whose consequence — a p99 that is optimistic against the same mean rate in
 * production — is invisible unless the page names which was asked for.
 */
private fun Plan.arrivalProcess(): String {
    // Asked before the seeds, because a replay has none and would otherwise
    // read as evenly spaced — which is the one thing a capture is not, and a
    // sentence the page would be wrong about on exactly the runs that went to
    // most trouble to be right.
    val replayed = arms.mapNotNull { it.profile }.flatMap { it.replays() }
    if (replayed.isNotEmpty()) {
        return replayed.joinToString(" ") { replay ->
            val scaling = if (replay.scaled == 1.0) "" else " at ${String.format(Locale.ROOT, "%.2f", replay.scaled)}x"
            val window = replay.window?.let { " ${it.forPlan()} from ${replay.from.forPlan()} in," }.orEmpty()
            "Arrivals were replayed from ${replay.series.source.escapedForHtml()}$scaling," +
                "$window whose own coefficient of variation was " +
                "${String.format(Locale.ROOT, "%.2f", replay.series.cov)}."
        }
    }
    return arms.mapNotNull { it.profile }.flatMap { it.seeds }.takeIf { it.isNotEmpty() }
        ?.let { drawn -> "Arrivals were drawn from ${"seed".plural(drawn.size)} ${drawn.joinToString(", ")}." }
        ?: "Arrivals were evenly spaced, which understates queueing against the same mean rate in production."
}

/** Every capture this shape replays, so a mix or a staged shape names each of them. */
private fun InjectionProfile.replays(): List<InjectionProfile.Replay> = when (this) {
    is InjectionProfile.Replay -> listOf(this)
    is InjectionProfile.Stages -> stages.flatMap { it.replays() }
    is InjectionProfile.Randomized -> of.replays()
    is InjectionProfile.ConstantRate, is InjectionProfile.RampRate, is InjectionProfile.ClosedUsers -> emptyList()
}

/** Measured from the departures that went out, so the claim above it has a number under it. */
private fun Arrivals.achieved(): String =
    if (count < 2L) ""
    else " Measured ${mean.forReport()} between departures, coefficient of variation " +
        "${String.format(Locale.ROOT, "%.2f", cov)}."

/** The shape in words, one clause per stage, in the order they run. */
private fun InjectionProfile.described(): String = when (this) {
    // Users rather than a rate, because a closed run asked for no rate: what
    // it asked for is this many users, and the target decided the rest.
    is InjectionProfile.ClosedUsers ->
        "$count ${if (count == 1) "user" else "users"} looping for ${over.forPlan()}"

    is InjectionProfile.ConstantRate -> "${perSecond.asRate()} held for ${over.forPlan()}"

    is InjectionProfile.RampRate -> "${from.asRate()} to ${to.asRate()} over ${over.forPlan()}"

    is InjectionProfile.Stages -> stages.joinToString(separator = ", then ") { it.described() }

    is InjectionProfile.Randomized -> of.described()

    is InjectionProfile.Replay -> {
        val scaling = if (scaled == 1.0) "" else " at ${String.format(Locale.ROOT, "%.2f", scaled)}x"
        "arrivals replayed from ${series.source.escapedForHtml()}$scaling over ${over.forPlan()}"
    }
}

/** One chart for one arm, and one apiece for a mix: two arms share a window and nothing else. */
private fun Plan.shapeCharts(): List<String> =
    arms.singleOrNull()?.profile?.shapeChart()
        ?: arms.flatMap { arm -> arm.profile?.shapeChart(arm.scenario).orEmpty() }

/**
 * The intent, drawn: rate against time, flat for a hold and sloped for a ramp.
 *
 * From the profile rather than from recorded traffic on purpose. It is what was
 * asked for, and putting it beside what happened is what makes a shortfall
 * something a reader sees rather than works out.
 */
private fun InjectionProfile.shapeChart(arm: String? = null): List<String> {
    // A capture names no corners: it is arrivals rather than a rate line, and
    // a flat line at its mean would draw a shape it never had. The arrivals
    // sentence says what happened instead.
    val points = corners().ifEmpty { return emptyList() }
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
    val named = arm?.let { "${it.escapedForHtml()} — " }.orEmpty()

    return listOf(
        """    <figure class="chart shape">""",
        """      <figcaption>${named}the shape that was asked for — peak ${peak.asRate()}</figcaption>""",
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

    // A capture has no rate line to draw corners of: what it has is arrivals,
    // and drawing a flat line at its mean would claim a shape it never had.
    // The chart is skipped and the arrivals line says what happened instead.
    is InjectionProfile.Replay -> emptyList()

    // Nothing to draw: the rate line of a closed run is what the target let it
    // reach, and that is measured rather than asked for. The page draws the
    // achieved rate instead, and says which it is looking at.
    is InjectionProfile.ClosedUsers -> emptyList()

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
