package io.github.matthewjones372.kestrel

import java.time.Instant
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * One bucket of a distribution: everything counted at or below [upperBound] and
 * above the bucket before it.
 *
 * The bound is where a percentile falling in this bucket would be reported, so
 * a chart drawn from these and a number printed beside it cannot disagree.
 */
data class Bucket(val upperBound: Duration, val count: Long, val trace: String? = null)

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

    /**
     * The worst relative error of any percentile read off [distribution] —
     * the width of the bucket every sample here is reported at the top of.
     *
     * Carried rather than looked up, because the guarantee otherwise stops at
     * the freeze: [Histogram] refuses to merge across precisions, and a frozen
     * value that did not know its own could be pooled with a coarser one to
     * produce a distribution half of one bucket scheme and half of another,
     * silently. It is also what a report has to print beside a number, and
     * reading it off the value beats a page knowing statically which numbers
     * came from which histogram.
     *
     * Null where nothing was counted: an empty timing has no bucket to be the
     * width of, and one that claimed a precision it never measured is the lie
     * this exists to stop. No default, for the same reason.
     */
    val precision: Double?,
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
     * The arithmetic mean, read off [distribution] rather than kept beside it.
     *
     * Every sample is counted at the top of the bucket it fell in, as every
     * percentile here is, so this is at or above the true mean and within the
     * histogram's own precision of it — never below. A mean kept as a running
     * sum would be exact and would be a second number that could disagree with
     * the buckets the rest of this value is read from.
     *
     * Zero where nothing was recorded, which the count beside it says.
     */
    val mean: Duration
        get() {
            if (count == 0L || distribution.isEmpty()) return Duration.ZERO

            val total = distribution.sumOf { it.upperBound.inWholeNanoseconds * it.count }
            return (total / count).nanoseconds
        }

    /**
     * A trace id belonging to a request that landed at [percentile], where the
     * run was traced and one was kept.
     *
     * The exemplar answers the question a percentile provokes: not "how slow
     * was the tail" but "show me one". Null where nothing was traced, and null
     * rather than a nearby bucket's id — an exemplar that is not from the
     * bucket asked about points a reader at the wrong request, which is worse
     * than pointing them nowhere.
     */
    fun exemplar(percentile: Double): String? {
        require(percentile in 0.0..HUNDRED) { "percentile must be in 0..100, but was $percentile" }
        if (count == 0L || distribution.isEmpty()) return null

        return distribution.bucketAtRank(maxOf(1L, ceil(percentile / HUNDRED * count).toLong())).trace
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
            precision = null,
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
    precision = precision,
)

/**
 * A timing over buckets that were counted somewhere else — added together from
 * several runs, or read back out of a file — with every percentile read off
 * them rather than carried alongside them.
 *
 * [precision] is asked for rather than assumed: these buckets came off some
 * histogram, and the caller is the only one left who knows which.
 */
fun List<Bucket>.timing(precision: Double?): Timing {
    val counted = sumOf { it.count }
    // Nothing counted, but the width is a property of the counter table rather
    // than of the samples: an empty coarse second still knows how wide it would
    // have been, and dropping that on the way through a merge would make the
    // merged run's timeline claim less than the run's did.
    if (counted == 0L) return Timing.none.copy(precision = precision)
    return Timing(
        count = counted,
        p50 = at(counted, P50),
        p95 = at(counted, P95),
        p99 = at(counted, P99),
        max = at(counted, HUNDRED),
        distribution = this,
        precision = precision,
    )
}

private fun List<Bucket>.at(count: Long, percentile: Double): Duration =
    valueAtRank(maxOf(1L, ceil(percentile / HUNDRED * count).toLong()))

/** The bucket the nth-smallest sample fell in. */
internal fun Timing.valueAtRank(rank: Long): Duration = distribution.valueAtRank(rank)

private fun List<Bucket>.valueAtRank(rank: Long): Duration = bucketAtRank(rank).upperBound

/** The bucket the nth-smallest sample fell in, rather than only its top. */
private fun List<Bucket>.bucketAtRank(rank: Long): Bucket =
    asSequence()
        .runningFold(0L to first()) { (seen, _), bucket -> (seen + bucket.count) to bucket }
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
     * How many times a body ran under this name, counted once however many
     * samples it reported.
     *
     * The number between [count] and [reached], and the only one that tells a
     * loop from a stream: a loop visits many times and samples once each, a
     * stream visits once and samples many times, and both make [count]
     * outnumber [reached] in exactly the same way. Zero where whatever recorded
     * the run did not count them, which the report prints as unmeasured rather
     * than as none — a baseline written before this existed is such a run.
     */
    val visits: Long = 0L,

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

    /**
     * How long this step's users spent waiting for a resource the generator
     * owns — a connection out of a pool, most often.
     *
     * Beside the latency rather than inside it, the way [RunResult.behind] sits
     * beside a percentile: a user queueing for something this end of the wire
     * rationed is not the target being slow, and a report that added the two
     * would say the database took four hundred milliseconds when it took three.
     * Absent where nothing waited, and where whatever recorded the run did not
     * count waits.
     */
    val queued: Timing = Timing.none,

    /**
     * How much the target sent back under this step, counted by the module that
     * made the request: rows for a query, an update count for an update.
     *
     * Beside [count], which is executions: a select that ran ten times and
     * returned a million rows is a different finding from one that ran a
     * million times. Zero where whatever recorded the run counted nothing,
     * which a report prints as unmeasured rather than as none.
     */
    val produced: Long = 0L,

    /** Records that departed and never reached the sink: the finding, not a gap in the samples. */
    val unmatched: Long = 0L,
    /** Records the run stopped waiting for, having left too late to be given the whole drain window. */
    val inFlight: Long = 0L,
    /** This step second by second, from the run's start. */
    val timeline: List<Second> = emptyList(),
) {
    val count: Long get() = ok.count + failed.count

    /**
     * Whether a body here reported more answers than it was run — a stream,
     * told apart from a loop, which is run many times and reports one answer
     * each.
     *
     * False where [visits] is zero, which is a run nobody counted them on
     * rather than a run with no stream in it: answering "no streams here" off a
     * number nobody took would put a finding on the page that nothing measured.
     */
    val streamed: Boolean get() = visits > 0L && count > visits

    /** How many failed for [reason]; none is zero rather than absent. */
    fun failedWith(reason: Reason): Long = failed.reasons[reason] ?: 0L
}

/** One arm as a plan carries it: what it sends, the names it can record under, and the rate it is sent at. */
data class PlannedArm(
    val scenario: String,
    val steps: List<String>,
    val profile: InjectionProfile?,
    /**
     * Whether this arm's scenario parks its users between steps.
     *
     * A pause is a step that records nothing, so nothing in a result can see
     * one afterwards — and a user inside a pause is a user in flight that is
     * making no request. Anything reading the in-flight count as requests in
     * flight has to know, so the plan carries it.
     */
    val pauses: Boolean = false,
    /** The waits this arm declares, so the page can name what was asked for. */
    val thinkTimes: List<ThinkTime> = emptyList(),
    /** What its drawn waits came from, where it draws any. */
    val thinkSeed: Long? = null,
    /** What its feeder made its data up from, where the run said so. */
    val drawn: List<Shape> = emptyList(),
) {

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

    /** Every shape the run said it drew its data from, every arm counted. */
    val drawn: List<Shape> get() = arms.flatMap { it.drawn }

    val plannedUsers: Long get() = arms.sumOf { it.plannedUsers }

    /** The window the profile asked for, and zero when no profile was named. */
    val plannedWindow: Duration get() = arms.maxOf { it.profile?.over ?: Duration.ZERO }

    /** An upper bound: a scenario that abandons users sends fewer, which is the point of showing it. */
    val plannedRequests: Long get() = arms.sumOf { it.plannedUsers * it.steps.size }

    /** Whether any arm parks its users, which stops in-flight users standing for in-flight requests. */
    val pauses: Boolean get() = arms.any { it.pauses }

    /**
     * How long the profile promised between departures: its window shared out
     * over the users it named, and zero where it named none.
     *
     * The rate line's own arithmetic rather than a second copy of it, so a
     * shape that spaces its departures some other way is still measured against
     * the spacing it actually asked for.
     */
    val plannedInterval: Duration
        get() = when {
            // A window shared out over a population is a number about nothing:
            // those users depart once each and then go round again whenever
            // the target lets them. Zero is what makes `lostGround`, `offered`
            // and `heldScheduleFor` fall away by construction rather than by a
            // special case in each of them.
            closed -> Duration.ZERO

            plannedUsers == 0L -> Duration.ZERO

            else -> plannedWindow / plannedUsers.toDouble()
        }

    /** Whether this run held a fixed population rather than promising departures. */
    val closed: Boolean get() = arms.any { it.profile is InjectionProfile.ClosedUsers }

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
     * How many users were running in each second of the run, sampled once a
     * second on the scheduler's side.
     *
     * The measured half of Little's law: throughput and latency come off the
     * histograms, and this is the count they predict. A second the sampler
     * missed is absent rather than zero, because nothing counted no users — it
     * counted nothing.
     */
    val usersInFlight: List<Long?> = emptyList(),

    /**
     * What the injector itself ran up against while it measured: descriptors,
     * ephemeral ports, its share of the cores.
     *
     * Beside [hiccups] because it answers the same kind of question — was this
     * number the target's, or this process's — and absent for a result built
     * from samples, which sampled nothing.
     */
    val limits: Limits = Limits.none,

    /**
     * What a fixed, target-free measurement took on this machine, where one was
     * taken. It travels into a baseline so a later run can ask whether it is on
     * a slower machine before it blames a step for the difference.
     */
    val probe: Probe? = null,

    /**
     * Which injector of how many measured this, where a run was split across
     * more than one.
     *
     * Absent rather than injector zero of one: a run nobody sharded is not a
     * distributed run with one member, and a file claiming otherwise would
     * merge with three others as though four had been asked for.
     */
    val shard: Shard? = null,

    /**
     * How many injectors sent the departures [behind] measured, where this
     * result is nobody's shard.
     *
     * One for an ordinary run, and the size of the set for the merge of one.
     * A merged run has no [shard] — it is the whole run again — and still has
     * to be judged on the spacing the lateness it carries was measured
     * against, which is the worst injector's. Where there is a shard it says
     * how many there were, and this is not consulted.
     */
    val injectors: Int = 1,
) {
    init {
        require(injectors > 0) { "a run is sent by at least one injector, but injectors was $injectors" }
    }

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
     *
     * A goal is usually one verdict. `inEveryStage` is one per stage, each
     * naming the stage it is about, so a run meets it only where every stage
     * did.
     */
    val verdicts: List<Verdict> get() {
        val settled = steady
        return plan.goals.flatMap { it.judgeAll(if (it.overSteadySegment) settled else this) }
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
    return behind.p99 > worst * MATERIAL
}

/**
 * How much of a tail the injector's own lateness has to be before it is worth
 * distrusting the tail.
 *
 * A judgement, and written here so it can be argued with. It was
 * [Histogram.PRECISION] — a bucket width, which says how small a difference this
 * tool can *see* and nothing at all about whether a difference matters. At
 * 0.78% a run whose lateness was a flat five milliseconds against a 356 ms tail
 * was called behind, while its own timeline was flat end to end, its concurrency
 * matched Little's law and it never lost ground. A verdict that fires on
 * one and a half percent is one people learn to route around, and this is the
 * verdict the tool exists to deliver.
 *
 * A twentieth of the tail is a tail worth distrusting. Where a run measured a
 * floor, [Floor] bounds this from below as well: lateness under what the machine
 * can resolve is not evidence of anything.
 */
const val MATERIAL: Double = 0.05

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
        val interval = ownInterval
        if (interval <= Duration.ZERO || latePerSecond.isEmpty()) return null

        val lost = latePerSecond.indexOfFirst { it.count > 0L && it.p99 > interval }
        return if (lost < 0) latePerSecond.size.seconds else lost.seconds
    }

/**
 * Whether the injector lost ground on the rate it promised: a p99 lateness
 * above one whole [ownInterval] is a departure of backlog at the tail, so the
 * load the profile named is not the load that left.
 *
 * The threshold for that judgement, in the one place it is stated. A different
 * question from [fellBehind], which measures the same backlog against the
 * target's own slowness: a fast target makes that gate impossible to pass and a
 * slow one hides real backlog, and neither says anything about the schedule.
 */
fun RunResult.lostGround(): Boolean = ownInterval > Duration.ZERO && behind.p99 > ownInterval

/**
 * The spacing the departures this result counted were actually asked for.
 *
 * The run's own interval, except where more than one injector sent it: shard
 * *k* of *N* sends every *N*th user, so it is judged against *N* intervals.
 * Reading the whole run's spacing off one injector's lateness would call a
 * host late that kept perfect time.
 */
val RunResult.ownInterval: Duration get() = plan.plannedInterval * (shard?.of ?: injectors)

/**
 * The bucket width this run's step percentiles were counted at, or null where
 * nothing was counted.
 *
 * Read off a value that was frozen from a histogram rather than off the
 * constant the recorder chose, so a page states the precision of the number it
 * is printing rather than one it was told to assume. `Runs` and `Shards` both
 * refuse to merge unlike widths, so one step's answers for all of them.
 */
val RunResult.precision: Double? get() = steps.values.firstNotNullOfOrNull { it.serviceTime.precision }

/**
 * The same for the timeline, which is counted an eighth of the counters wide
 * and so eight times the bucket. Null where no second was recorded.
 */
val RunResult.timelinePrecision: Double? get() = timeline.firstNotNullOfOrNull { it.okServiceTime.precision }

private const val HUNDRED = 100.0
private const val P50 = 50.0
private const val P95 = 95.0
private const val P99 = 99.0
private const val P999 = 99.9
