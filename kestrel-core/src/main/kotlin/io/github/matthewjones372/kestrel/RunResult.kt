package io.github.matthewjones372.kestrel

import java.time.Instant
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One bucket of a distribution: everything counted at or below [upperBound] and
 * above the bucket before it.
 *
 * The bound is where a percentile falling in this bucket would be reported, so
 * a chart drawn from these and a number printed beside it cannot disagree.
 */
data class Bucket(val upperBound: Duration, val count: Long)

/** A percentile a run may not have the samples for: absent carries the reason, so no reader has to invent one. */
sealed interface Tail {

    data class Measured(val duration: Duration) : Tail

    data class Absent(val because: String) : Tail
}

/**
 * A share of samples a run may have taken none of: absent carries the reason,
 * so no reader has to invent one.
 *
 * Its own type rather than [Tail]'s, which measures a duration; this measures a
 * fraction of the samples, and one type holding either would say neither.
 */
sealed interface Met {

    data class Measured(val fraction: Double) : Met

    data class Absent(val because: String) : Met
}

/** A histogram read once and frozen: percentiles that cannot move under a reader. */
data class Timing(
    val count: Long,
    val p50: Duration,
    val p95: Duration,
    val p99: Duration,
    val max: Duration,
    /** What was counted, bucket by bucket: what a chart draws, and what [percentile] is read from. */
    val distribution: List<Bucket>,
) {

    /**
     * The 99.9th percentile, which is where two JVM collectors that match to
     * p99 separate — and the first percentile here a run can be too short to
     * have measured.
     */
    val p999: Tail
        get() = if (count < SAMPLES_FOR_P999) {
            Tail.Absent("only $count samples, and under $SAMPLES_FOR_P999 the top bucket holds fewer than one")
        } else {
            Tail.Measured(percentile(P999))
        }

    /**
     * The top of the bucket [percentile] fell in, read off [distribution] — so
     * a frozen timing answers a percentile nobody asked for at freeze time, by
     * the arithmetic that produced the ones that were.
     */
    fun percentile(percentile: Double): Duration {
        require(percentile in 0.0..HUNDRED) { "percentile must be in 0..100, but was $percentile" }
        if (count == 0L || distribution.isEmpty()) return Duration.ZERO

        return valueAtRank(maxOf(1L, ceil(percentile / HUNDRED * count).toLong()))
    }

    /**
     * The share of samples that came back at or below [under], read off
     * [distribution].
     *
     * The bucket [under] falls inside counts as having missed it. Every sample
     * in that bucket is reported at its top, which is above the target, so this
     * rounds the way [percentile] does: away from the target rather than
     * towards it, which is the direction an approximation can be quoted in.
     */
    fun share(under: Duration): Met {
        if (count == 0L || distribution.isEmpty()) {
            return Met.Absent("nothing was recorded, so no share of it met anything")
        }

        val met = distribution.takeWhile { it.upperBound <= under }.sumOf { it.count }
        return Met.Measured(met.toDouble() / count)
    }

    companion object {
        /** Below this a p99.9 is one sample in a thousand taken from fewer than a thousand. */
        const val SAMPLES_FOR_P999: Long = 1_000L

        /** Nothing was measured, which a count of zero says and every percentile below it repeats. */
        val none: Timing = Timing(
            count = 0L,
            p50 = Duration.ZERO,
            p95 = Duration.ZERO,
            p99 = Duration.ZERO,
            max = Duration.ZERO,
            distribution = emptyList(),
        )
    }
}

fun Histogram.timing(): Timing = Timing(
    count = count,
    p50 = percentile(P50),
    p95 = percentile(P95),
    p99 = percentile(P99),
    max = max,
    distribution = distribution(),
)

/**
 * A timing over buckets that were counted somewhere else — added together from
 * several runs, or read back out of a file — with every percentile read off
 * them rather than carried alongside them.
 */
fun List<Bucket>.timing(): Timing {
    val counted = sumOf { it.count }
    if (counted == 0L) return Timing.none
    return Timing(
        count = counted,
        p50 = at(counted, P50),
        p95 = at(counted, P95),
        p99 = at(counted, P99),
        max = at(counted, HUNDRED),
        distribution = this,
    )
}

private fun List<Bucket>.at(count: Long, percentile: Double): Duration =
    valueAtRank(maxOf(1L, ceil(percentile / HUNDRED * count).toLong()))

/** The bucket the nth-smallest sample fell in. */
internal fun Timing.valueAtRank(rank: Long): Duration = distribution.valueAtRank(rank)

private fun List<Bucket>.valueAtRank(rank: Long): Duration =
    asSequence()
        .runningFold(0L to first().upperBound) { (seen, _), bucket -> (seen + bucket.count) to bucket.upperBound }
        .first { (seen, _) -> seen >= rank }
        .second

/**
 * One second of a run, counted from the run's start.
 *
 * A second nothing ran in is present and zero rather than missing: a gap in a
 * line is information and a dropped point is a lie about the shape.
 *
 * What the target took is read from a histogram good to
 * [Histogram.COARSE_PRECISION] rather than the [Histogram.PRECISION] of the
 * summary above — a full table a second per step is tens of megabytes of
 * counters. Anything quoted from here carries that error bar.
 *
 * The buckets are kept and not only the percentiles read off them, because
 * every way of adding seconds together adds buckets: a stretch of one run
 * re-read as its steady segment, and second *n* of ten runs merged into second
 * *n* of one. Two seconds' percentiles cannot be averaged into a third. Only
 * the buckets that counted something survive the freeze, which is tens of them
 * a second rather than the table.
 *
 * The two sides are apart here for the reason [StepStats] keeps them apart: a
 * second of shed load is a second of fast rejections, and one distribution
 * holding both would report a percentile nobody experienced.
 */
data class Second(
    val okServiceTime: Timing,
    val failedServiceTime: Timing,
    val okResponseTime: Timing,
    val failedResponseTime: Timing,
) {

    val ok: Long get() = okServiceTime.count

    val failed: Long get() = failedServiceTime.count

    val count: Long get() = ok + failed

    /** Both sides added back together, which is what a line drawn over the run is. */
    val serviceTime: Timing get() = listOf(okServiceTime, failedServiceTime).merged()

    /**
     * The same requests measured from the departure the profile promised, which
     * is the clock every percentile goal reads by default.
     */
    val responseTime: Timing get() = listOf(okResponseTime, failedResponseTime).merged()

    val p50: Duration get() = serviceTime.p50

    val p99: Duration get() = serviceTime.p99
}

/**
 * One side of a step — the requests that worked, or the ones that did not — and
 * how long that side took.
 *
 * A target shedding load answers fast, so a rejection counted in the same
 * histogram as a success pulls the whole distribution down and the run reports
 * a percentile nobody experienced.
 */
data class Outcome(
    val serviceTime: Timing,
    val responseTime: Timing,
    /** What the target said, counted by reason. Empty on the side that worked. */
    val reasons: Map<Reason, Long> = emptyMap(),
) {
    val count: Long get() = serviceTime.count

    companion object {
        /** No request ended this way. */
        val none: Outcome = Outcome(Timing.none, Timing.none)
    }
}

/**
 * What one step did. `serviceTime` is what the target took; `responseTime` is
 * measured from the departure the profile promised, so a generator that fell
 * behind reports it here rather than as the target being fast.
 *
 * Both of those count every sample, and [ok] and [failed] hold the same samples
 * split by how they ended: the whole-step timings are the merge of the two.
 */
data class StepStats(
    val name: String,
    val ok: Outcome,
    val failed: Outcome,
    val serviceTime: Timing,
    val responseTime: Timing,
    /**
     * The users that got this far, counted once each however many requests they
     * made here.
     *
     * Beside [count] rather than instead of it: under a loop [count] is a
     * multiple of the users and under a condition it is a fraction of them, and
     * a step that made few requests because few users reached it is a different
     * finding from one each of them made few requests at. Zero where whatever
     * recorded the run did not count users, which the report prints as unmeasured
     * rather than as nobody.
     */
    val reached: Long = 0L,

    /**
     * The round trips behind [count]: one per request, and more where a step
     * followed a redirect or retried.
     *
     * Beside [count] rather than folded into it, because a retry folded into
     * one measurement reports the target as slower than it is and hides that
     * it answered wrongly first. Zero where whatever recorded the run did not
     * count them, which the report prints as unmeasured rather than as none.
     */
    val attempts: Long = 0L,

    /** Records that departed and never reached the sink: the finding, not a gap in the samples. */
    val unmatched: Long = 0L,
    /** Records the run stopped waiting for, having left too late to be given the whole drain window. */
    val inFlight: Long = 0L,
    /** This step second by second, from the run's start. */
    val timeline: List<Second> = emptyList(),
) {
    val count: Long get() = ok.count + failed.count

    /** How many failed for [reason]; none is zero rather than absent. */
    fun failedWith(reason: Reason): Long = failed.reasons[reason] ?: 0L
}

/** One arm as a plan carries it: what it sends, the names it can record under, and the rate it is sent at. */
data class PlannedArm(val scenario: String, val steps: List<String>, val profile: InjectionProfile?) {

    val plannedUsers: Long get() = profile?.userCount() ?: 0L
}

/**
 * What a run was asked to do, carried alongside what it did.
 *
 * A profile that promised 480 users and a run that sent 300 are the same page
 * without this, and every latency on that page would be describing a lighter
 * test than the one somebody asked for.
 */
data class Plan(
    val arms: List<PlannedArm>,
    val goals: List<Goal> = emptyList(),
    val warmUp: WarmUp? = null,
) {

    constructor(
        scenario: String,
        steps: List<String>,
        profile: InjectionProfile?,
        goals: List<Goal> = emptyList(),
    ) : this(listOf(PlannedArm(scenario, steps, profile)), goals)

    init {
        require(arms.isNotEmpty()) { "a plan describes at least one arm" }
    }

    /** The first arm's scenario; a mix has one per arm, on [PlannedArm]. */
    val scenario: String get() = arms.first().scenario

    /** Every name the run can record under, every arm counted: step names are unique across a mix. */
    val steps: List<String> get() = arms.flatMap { it.steps }

    /** The first arm's rate line, likewise. */
    val profile: InjectionProfile? get() = arms.first().profile

    val plannedUsers: Long get() = arms.sumOf { it.plannedUsers }

    /** The window the profile asked for, and zero when no profile was named. */
    val plannedWindow: Duration get() = arms.maxOf { it.profile?.over ?: Duration.ZERO }

    /** An upper bound: a scenario that abandons users sends fewer, which is the point of showing it. */
    val plannedRequests: Long get() = arms.sumOf { it.plannedUsers * it.steps.size }

    /**
     * How long the profile promised between departures: its window shared out
     * over the users it named, and zero where it named none.
     *
     * The rate line's own arithmetic rather than a second copy of it, so a
     * shape that spaces its departures some other way is still measured against
     * the spacing it actually asked for.
     */
    val plannedInterval: Duration
        get() = if (plannedUsers == 0L) Duration.ZERO else plannedWindow / plannedUsers.toDouble()

    companion object {
        /** For a result built from samples rather than run, which claims nothing. */
        val none: Plan = Plan(scenario = "", steps = emptyList(), profile = null)
    }
}

/** What a run measured, as a value: assert on it, diff it, hand it to a report. */
data class RunResult(
    val startedAt: Instant,
    val steps: Map<String, StepStats>,
    val behind: Timing,
    val plan: Plan = Plan.none,
    val arrivals: Arrivals = Arrivals.none,
    /** What measured it, so a comparison against a run from another machine can say so. */
    val machine: Machine = Machine.here(),

    /**
     * What the injector's own JVM stalled for while this ran, measured off the
     * timed path so a stall in the measuring process sits beside the tail it
     * caused rather than inside it. Empty for a result built from samples.
     */
    val hiccups: Timing = Timing.none,

    /** Every step together, second by second, from the run's start. */
    val timeline: List<Second> = emptyList(),

    /**
     * How late that second's departures were, second by second, from the run's
     * start.
     *
     * [behind] over the whole run says a schedule was lost and cannot say
     * when, which leaves a reader with "lower the rate" and no idea which rate
     * held. Coarse, like the rest of the timeline, and empty for a result
     * built from samples rather than run.
     */
    val latePerSecond: List<Timing> = emptyList(),

    /**
     * What a fixed, target-free measurement took on this machine, where one was
     * taken. It travels into a baseline so a later run can ask whether it is on
     * a slower machine before it blames a step for the difference.
     */
    val probe: Probe? = null,
) {
    val count: Long get() = steps.values.sumOf { it.count }

    val ok: Long get() = steps.values.sumOf { it.ok.count }

    val failed: Long get() = count - ok

    operator fun get(step: String): StepStats = requireNotNull(steps[step]) {
        "no step named '$step' ran; this simulation had ${steps.keys.sorted()}"
    }

    /**
     * The same lookup, through the handle that declared the step. A handle
     * proves the name was written once; it does not prove the step was
     * reached, so this still throws when nothing ran under it.
     */
    operator fun get(step: StepName): StepStats = get(step.name)

    /**
     * Whether anything was recorded under [step]. Beside `get` rather than
     * instead of it: a step that never ran is a different fact from one that
     * ran and failed, and a test asking this is making the first claim.
     */
    fun ran(step: String): Boolean = steps.containsKey(step)

    fun ran(step: StepName): Boolean = ran(step.name)

    /**
     * Each goal the simulation declared, judged against what happened — over
     * [steady] where the timeline measured what the goal reads, and over the
     * whole run otherwise, which is what a run that never settled gets for all
     * of them.
     */
    val verdicts: List<Verdict> get() {
        val settled = steady
        return plan.goals.map { it.judge(if (it.overSteadySegment) settled else this) }
    }

    /** True when every goal was met, and when there were none to miss. */
    val metEveryGoal: Boolean get() = verdicts.all { it.met }
}

/**
 * The steps holding records the run could not account for. Every sink asks this
 * rather than each deciding when a zero is worth a line, so a run that lost
 * records on the page lost them in the job summary too.
 */
val RunResult.unanswered: List<StepStats>
    get() = steps.values.filter { it.unmatched > 0L || it.inFlight > 0L }

/** How many records departed and never arrived, across the whole run. */
val RunResult.unmatched: Long get() = steps.values.sumOf { it.unmatched }

/** How many the run stopped waiting for, across the whole run. */
val RunResult.inFlight: Long get() = steps.values.sumOf { it.inFlight }

/**
 * Whether the generator's own backlog is large enough to have moved a number a
 * report prints. The gate is the histogram's error bar: under that the delay
 * cannot show up in a percentile beside it, and a warning that does not show up
 * in the numbers next to it is one readers learn to skip.
 *
 * Every sink asks this rather than each inventing a threshold, so a run that is
 * behind on the page is behind in the job summary too.
 *
 * This is the injector being behind, not the pipeline: it says departures left
 * late. A pipeline falling behind is what the latency of a completion step
 * measures, which is the number this warning sits above.
 */
fun RunResult.fellBehind(): Boolean {
    val worst = steps.values.maxOfOrNull { it.responseTime.p99 } ?: return false
    return behind.p99 > worst * Histogram.PRECISION
}

/**
 * Whether the injector lost ground on the rate it promised: a p99 lateness
 * above one whole [Plan.plannedInterval] is a departure of backlog at the tail,
 * so the load the profile named is not the load that left.
 *
 * The threshold for that judgement, in the one place it is stated. A different
 * question from [fellBehind], which measures the same backlog against the
 * target's own slowness: a fast target makes that gate impossible to pass and a
 * slow one hides real backlog, and neither says anything about the schedule.
 */

/**
 * How long the run kept the schedule it promised, before the first second
 * whose departures were a whole planned interval late at p99.
 *
 * [lostGround]'s rule read second by second rather than over the whole run.
 * The whole window where no second lost ground, and absent where nothing was
 * planned or nothing was recorded — a run with no schedule to keep did not
 * keep one for zero seconds.
 *
 * The rate that held is deliberately not derived from it here: under a ramp
 * that is the profile's rate at this offset, and no shape answers that yet.
 */
val RunResult.heldScheduleFor: Duration?
    get() {
        val interval = plan.plannedInterval
        if (interval <= Duration.ZERO || latePerSecond.isEmpty()) return null

        val lost = latePerSecond.indexOfFirst { it.count > 0L && it.p99 > interval }
        return if (lost < 0) latePerSecond.size.seconds else lost.seconds
    }

fun RunResult.lostGround(): Boolean =
    plan.plannedInterval > Duration.ZERO && behind.p99 > plan.plannedInterval

private const val HUNDRED = 100.0
private const val P50 = 50.0
private const val P95 = 95.0
private const val P99 = 99.0
private const val P999 = 99.9
