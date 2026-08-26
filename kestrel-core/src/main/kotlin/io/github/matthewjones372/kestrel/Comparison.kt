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

/** Every step of this run against the same step of [baseline], at [percentile]. */
fun RunResult.against(baseline: RunResult, percentile: Double = P99): List<Change> {
    val names = (steps.keys + baseline.steps.keys).sorted()

    return names.map { name ->
        val now = steps[name]
        val before = baseline.steps[name]
        when {
            before == null -> Change.Added(name)
            now == null -> Change.Gone(name)
            else -> compare(name, before.responseTime, now.responseTime, percentile)
        }
    }
}

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
