package io.github.matthewjones372.kestrel

import kotlin.time.Duration

/**
 * An action that knows where it sends.
 *
 * Core cannot read a URL — an [Action] is a lambda to it, and the module that
 * carries an HTTP client is the one that knows a host. So core declares the
 * question and each adapter answers it, which is the same seam [Engine] and
 * [Transport] already sit on.
 */
interface Targeted {

    /** The host this action sends to, as written rather than resolved. */
    val host: String

    /**
     * Every host this action may reach.
     *
     * [host] alone for anything that sends to one. A bootstrap list is several,
     * and a fence shown the first of them is a fence with a hole in the shape
     * of the ones it was not shown.
     */
    val hosts: List<String> get() = listOf(host)
}

/** What a plan would do, asked before it does any of it. */
sealed interface Preview {

    data class Allowed(
        val users: Long,
        val over: Duration,

        /**
         * The tallest rate the plan reaches, not its mean — a fence that
         * averaged a ramp would allow a peak nobody agreed to.
         *
         * Null for a closed run, whose departure rate is the target's to
         * decide and not the profile's to state.
         */
        val peakRate: Rate?,

        /**
         * The fewest requests this plan can send.
         *
         * A lower bound rather than a count, because [requestsBounded] says
         * whether an upper one exists at all.
         */
        val requestsAtLeast: Long,

        /**
         * Whether the number above is also the most it can send.
         *
         * False where a scenario loops on something the run decides —
         * `during`, or a `doIf` nobody can evaluate yet. A fence cannot bound
         * what it cannot count, and saying so is the difference between a
         * preview and a guess.
         */
        val requestsBounded: Boolean,

        /** Every host this plan would send to, as its actions name them. */
        val hosts: List<String>,

        /**
         * Steps that named no host.
         *
         * Not "allowed": unknown. A step whose body calls a client of the
         * caller's own is invisible from here, and a preview that quietly
         * counted it as safe would be the reassurance this exists to avoid.
         */
        val untargeted: Int,
    ) : Preview

    data class Refused(val reason: Refusal) : Preview
}

/**
 * What [this] would send, and whether [allowance] permits it.
 *
 * Sends nothing. A scenario is a value, so every number here is arithmetic over
 * the plan rather than a measurement of it.
 */
fun Simulation.preview(allowance: Allowance = Allowance.none): Preview {
    val targets = arms.flatMap { it.scenario.steps.targets() }
    val hosts = targets.filterNotNull().distinct().sorted()
    val users = arms.sumOf { it.profile.userCount() }
    val peak = arms.mapNotNull { it.profile.peakRate() }.maxByOrNull { it.perSecond }
    val over = arms.maxOf { it.profile.over }
    val requests = arms.sumOf { it.profile.userCount() * it.scenario.steps.leastRequests() }
    val bounded = arms.all { it.scenario.steps.countable() }

    allowance.refuses(hosts, peak, over, requests, bounded)?.let { return Preview.Refused(it) }

    return Preview.Allowed(
        users = users,
        over = over,
        peakRate = peak,
        requestsAtLeast = requests,
        requestsBounded = bounded,
        hosts = hosts,
        untargeted = targets.count { it == null },
    )
}

private fun Allowance.refuses(
    hosts: List<String>,
    peak: Rate?,
    over: Duration,
    requests: Long,
    bounded: Boolean,
): Refusal? = overHost(hosts) ?: overRate(peak) ?: overWindow(over) ?: overRequests(requests, bounded)

private fun Allowance.overHost(asked: List<String>): Refusal? =
    asked.firstOrNull { !allows(it) }?.let { Refusal.HostNotAllowed(it, hosts) }

private fun Allowance.overRate(peak: Rate?): Refusal? = maxRate
    ?.takeIf { peak != null && peak.perSecond > it.perSecond }
    ?.let { Refusal.OverRate(asked = requireNotNull(peak), allowed = it) }

private fun Allowance.overWindow(asked: Duration): Refusal? = maxDuration
    ?.takeIf { asked > it }
    ?.let { Refusal.OverDuration(asked = asked, allowed = it) }

/**
 * An unbounded scenario against a request cap is refused rather than allowed on
 * its lower bound: a fence that cannot count cannot fence.
 */
private fun Allowance.overRequests(asked: Long, bounded: Boolean): Refusal? = maxRequests
    ?.takeIf { asked > it || !bounded }
    ?.let { Refusal.OverRequests(asked = asked, allowed = it) }

/** The tallest rate a profile reaches, or null where the target decides it. */
private fun InjectionProfile.peakRate(): Rate? = when (this) {
    is InjectionProfile.ConstantRate -> perSecond.perSecond

    is InjectionProfile.RampRate -> maxOf(from, to).perSecond

    is InjectionProfile.Stages -> stages.mapNotNull { it.peakRate() }.maxByOrNull { it.perSecond }

    is InjectionProfile.Randomized -> of.peakRate()

    // Derived exactly: the window and how many arrived in it are both known.
    is InjectionProfile.Replay ->
        if (over > Duration.ZERO) (taken.size / over.inWholeMilliseconds.toDouble() * MILLIS_A_SECOND).perSecond
        else null

    is InjectionProfile.ClosedUsers -> null
}

private fun List<Step>.targets(): List<String?> = flatMap { step ->
    when (step) {
        is Step.Exec -> step.action.targets()
        is Step.Emit -> step.action.targets()
        is Step.Pause -> emptyList()
        is Step.Repeat -> step.steps.targets()
        is Step.During -> step.steps.targets()
        is Step.When -> step.steps.targets()
    }
}

/**
 * The hosts one action names, or a single null where it names none.
 *
 * The null is what [Preview.Allowed.untargeted] counts, so an action that names
 * several hosts is still one step that named some.
 */
private fun Action.targets(): List<String?> =
    (this as? Targeted)?.hosts?.ifEmpty { listOf(null) } ?: listOf(null)

/** The fewest requests one user makes walking these steps. */
private fun List<Step>.leastRequests(): Long = sumOf { step ->
    when (step) {
        is Step.Exec -> 1L

        is Step.Emit -> 1L

        is Step.Pause -> 0L

        is Step.Repeat -> step.steps.leastRequests() * step.times

        // A window and a condition both bottom out at nothing.
        is Step.During -> 0L

        is Step.When -> 0L
    }
}

/** Whether the fewest is also the most. */
private fun List<Step>.countable(): Boolean = all { step ->
    when (step) {
        is Step.Exec -> true
        is Step.Emit -> true
        is Step.Pause -> true
        is Step.Repeat -> step.steps.countable()
        is Step.During -> false
        is Step.When -> false
    }
}

private const val MILLIS_A_SECOND = 1000.0

/**
 * What became of an attempt to run inside an [Allowance].
 *
 * Its own type rather than a widened [RunResult]: the refusal has to be a
 * value, because a caller asking to run within a fence was promised it might be
 * turned away, and a `run` that returned a sum would delete a line from a
 * published `.api` file for every caller that never asked for one.
 */
sealed interface Ran {

    data class Result(val result: RunResult) : Ran

    data class Refused(val reason: Refusal) : Ran
}
