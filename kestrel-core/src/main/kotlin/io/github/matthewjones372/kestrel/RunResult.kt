package io.github.matthewjones372.kestrel

import java.time.Instant
import kotlin.math.ceil
import kotlin.time.Duration

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

    companion object {
        /** Below this a p99.9 is one sample in a thousand taken from fewer than a thousand. */
        const val SAMPLES_FOR_P999: Long = 1_000L
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

/** The bucket the nth-smallest sample fell in. */
internal fun Timing.valueAtRank(rank: Long): Duration =
    distribution.asSequence()
        .runningFold(0L to distribution.first().upperBound) { (seen, _), bucket ->
            (seen + bucket.count) to bucket.upperBound
        }
        .first { (seen, _) -> seen >= rank }
        .second

/**
 * What one step did. `serviceTime` is what the target took; `responseTime` is
 * measured from the departure the profile promised, so a generator that fell
 * behind reports it here rather than as the target being fast.
 */
data class StepStats(
    val name: String,
    val count: Long,
    val ok: Long,
    val failures: Map<String, Long>,
    val serviceTime: Timing,
    val responseTime: Timing,
) {
    val failed: Long get() = count - ok

    /** How many failed for [reason]; none is zero rather than absent. */
    fun failedWith(reason: String): Long = failures[reason] ?: 0L
}

/**
 * What a run was asked to do, carried alongside what it did.
 *
 * A profile that promised 480 users and a run that sent 300 are the same page
 * without this, and every latency on that page would be describing a lighter
 * test than the one somebody asked for.
 */
data class Plan(
    val scenario: String,
    val steps: List<String>,
    val profile: InjectionProfile?,
    val goals: List<Goal> = emptyList(),
) {
    val plannedUsers: Long get() = profile?.userCount() ?: 0L

    /** An upper bound: a scenario that abandons users sends fewer, which is the point of showing it. */
    val plannedRequests: Long get() = plannedUsers * steps.size

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
) {
    val count: Long get() = steps.values.sumOf { it.count }

    val ok: Long get() = steps.values.sumOf { it.ok }

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

    /** Each goal the simulation declared, judged against what happened. */
    val verdicts: List<Verdict> get() = plan.goals.map { it.judge(this) }

    /** True when every goal was met, and when there were none to miss. */
    val metEveryGoal: Boolean get() = verdicts.all { it.met }
}

/**
 * Whether the generator's own backlog is large enough to have moved a number a
 * report prints. The gate is the histogram's error bar: under that the delay
 * cannot show up in a percentile beside it, and a warning that does not show up
 * in the numbers next to it is one readers learn to skip.
 *
 * Every sink asks this rather than each inventing a threshold, so a run that is
 * behind on the page is behind in the job summary too.
 */
fun RunResult.fellBehind(): Boolean {
    val worst = steps.values.maxOfOrNull { it.responseTime.p99 } ?: return false
    return behind.p99 > worst * Histogram.PRECISION
}

private const val HUNDRED = 100.0
private const val P50 = 50.0
private const val P95 = 95.0
private const val P99 = 99.0
private const val P999 = 99.9
