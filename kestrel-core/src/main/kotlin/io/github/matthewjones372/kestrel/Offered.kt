package io.github.matthewjones372.kestrel

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The load a run asked for beside the load that actually left, and the window
 * it took to leave.
 *
 * What a reader needs when the generator fell behind. Today a run that lost its
 * schedule says so and stops there, which is a diagnosis with no treatment: the
 * numbers under the warning are still true about *something*, and this names
 * what. Service time was measured from the departure that happened, so it
 * describes the target at [left] rather than at [asked] — a smaller experiment
 * than the one somebody asked for, and a real one.
 *
 * Both rates are requests rather than users, because both sides are counted the
 * same way: [asked] is the plan's requests over the window it named, and [left]
 * is what the timeline counted over the window it took. For the one-step
 * scenario that is also users a second; for a longer journey it is not, and
 * saying "requests" is the honest half of that.
 */
data class Offered(val asked: Rate, val left: Rate, val over: Duration) {

    /** How much of the asked-for load actually left, as a share of one. */
    val share: Double get() = if (asked.perSecond == 0.0) 0.0 else left.perSecond / asked.perSecond
}

/**
 * What this run offered, or nothing where it cannot be said.
 *
 * Derived rather than recorded: the plan and the timeline are both on the
 * result already, and a third copy of a number they imply is a number that can
 * disagree with them. Absent for a result with no plan or no timeline, which
 * is a result built from samples rather than run — for those, nothing measured
 * a rate and zero would be a claim.
 */
val RunResult.offered: Offered?
    get() {
        val window = plan.plannedWindow
        val took = timeline.size.seconds
        if (window <= Duration.ZERO || took <= Duration.ZERO) return null

        val counted = timeline.sumOf { it.count }
        return Offered(
            // One injector's share of what the run was asked for, where a run
            // was split: every injector carries the whole plan unmodified, so
            // the share is derived here rather than written into a plan that
            // would then differ from its neighbours' and refuse to merge.
            asked = (
                plan.plannedRequests.toDouble() / (shard?.of ?: 1) /
                    window.inWholeMilliseconds.toDouble() * MILLIS_PER_SECOND
                ).perSecond,
            left = (counted / took.inWholeMilliseconds.toDouble() * MILLIS_PER_SECOND).perSecond,
            over = took,
        )
    }

private const val MILLIS_PER_SECOND = 1_000.0
