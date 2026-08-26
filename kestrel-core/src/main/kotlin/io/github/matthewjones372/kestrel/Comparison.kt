package io.github.matthewjones372.kestrel

import kotlin.time.Duration

/** What became of one step's percentile between two runs. */
sealed interface Change {

    val step: String

    /**
     * The intervals overlap, so the two runs have not been shown to differ.
     *
     * Not "the same": a run this short cannot tell. The fix is a longer run,
     * and saying this rather than a percentage is what stops a team acting on
     * noise every release.
     */
    data class Indistinguishable(
        override val step: String,
        val before: Duration,
        val now: Duration,
    ) : Change

    data class Worse(
        override val step: String,
        val before: Duration,
        val now: Duration,
        val interval: Interval,
    ) : Change

    data class Better(
        override val step: String,
        val before: Duration,
        val now: Duration,
        val interval: Interval,
    ) : Change

    /** A step this run has and the baseline did not. */
    data class Added(override val step: String) : Change

    /** A step the baseline had and this run does not — the most interesting change there is. */
    data class Gone(override val step: String) : Change
}

/**
 * Two runs compared, or the reason comparing them would mean nothing.
 *
 * An unlike plan refuses and an unlike machine only warns: a run asked to do
 * different work measured something else entirely, while a team whose runners
 * are all shared would otherwise never get a comparison at all.
 */
sealed interface Comparison {

    /** The two runs were not asked for the same work, and [why] names what differs. */
    data class NotComparable(val why: String) : Comparison

    /** Every step of a run measured on [now], against a baseline measured on [before]. */
    data class Compared(val changes: List<Change>, val before: Machine, val now: Machine) : Comparison {

        /** What to distrust [changes] by, or null when one machine measured both runs. */
        val caveat: String?
            get() = if (before == now) {
                null
            } else {
                "measured on $now and the baseline on $before, so every delta here may be the runner"
            }
    }
}

/** Every step of this run against the same step of [baseline], at [percentile]. */
fun RunResult.against(baseline: RunResult, percentile: Double = P99): Comparison {
    val unlike = plan.unlike(baseline.plan)
    if (unlike.isNotEmpty()) {
        return Comparison.NotComparable("these runs were not asked to do the same thing: ${unlike.joinToString()}")
    }

    val names = (steps.keys + baseline.steps.keys).sorted()
    return Comparison.Compared(
        changes = names.map { name ->
            val now = steps[name]
            val before = baseline.steps[name]
            when {
                before == null -> Change.Added(name)
                now == null -> Change.Gone(name)
                else -> compare(name, before.responseTime, now.responseTime, percentile)
            }
        },
        before = baseline.machine,
        now = machine,
    )
}

/**
 * What this plan asked for that [other] did not. Goals are left out: a
 * threshold is what a team wanted of the numbers, not work sent at the target.
 */
private fun Plan.unlike(other: Plan): List<String> = listOfNotNull(
    "scenario".difference(other.scenario, scenario),
    "steps".difference(other.steps, steps),
    "profile".difference(other.profile, profile),
)

private fun String.difference(before: Any?, now: Any?): String? =
    if (before == now) null else "$this was $before, now $now"

private fun compare(step: String, before: Timing, now: Timing, percentile: Double): Change {
    val wasIn = before.interval(percentile)
    val isIn = now.interval(percentile)
    val was = before.percentile(percentile)
    val became = now.percentile(percentile)

    // Without both intervals there is nothing to say beyond the numbers, and
    // claiming a direction from two points is the thing this exists to stop.
    if (wasIn == null || isIn == null || wasIn overlaps isIn) {
        return Change.Indistinguishable(step, was, became)
    }
    return if (became > was) Change.Worse(step, was, became, isIn) else Change.Better(step, was, became, isIn)
}

private const val P99 = 99.0
