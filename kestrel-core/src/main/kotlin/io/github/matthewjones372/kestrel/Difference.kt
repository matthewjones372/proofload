package io.github.matthewjones372.kestrel

import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.time.Duration

/**
 * One number a comparison reads off a set of runs, implemented by the same
 * values that declare a goal.
 *
 * Sealed rather than a lambda: a statistic has to answer more than "what is the
 * number" — which direction is bad, and what a machine's own noise could fake.
 */
sealed interface Statistic {

    /** What this reads, in the words a report prints. */
    val described: String

    /** Which way is bad: a slower percentile is worse, and less goodput is worse. */
    val higherIsWorse: Boolean

    /** How a report reads this having gone up, and having gone down: "slower" and "faster". */
    val moreIs: String

    val lessIs: String

    /** What this reads out of one run, or null when that run never ran the step. */
    fun samplesIn(run: RunResult): Samples?

    /** The number, off one run's counters or off several runs' added, or null where nothing measured it. */
    fun read(samples: Samples): Double?
}

/**
 * The counters a [Statistic] is read from: one run's, or several runs' added.
 *
 * Buckets rather than a percentile, for the reason [Runs.merged] holds them: a
 * percentile of a population cannot be recovered from the percentiles of the
 * runs that made it.
 */
data class Samples(val timing: Timing, val count: Long, val failed: Long)

/**
 * The range a ratio could plausibly sit in, given how far the runs behind it
 * landed apart. [Interval] is the same question inside one run, over durations
 * rather than ratios, and the two are not interchangeable.
 */
data class Spread(val low: Double, val high: Double) {

    operator fun contains(ratio: Double): Boolean = ratio in low..high
}

/**
 * What a set of runs can say about a change: better, worse, or that it cannot
 * tell. The third is the feature — a comparison that always answers better or
 * worse is the last place in this tool where an unmeasured number gets printed.
 */
sealed interface Tell {

    data object Better : Tell

    data object Worse : Tell

    /** [wouldChangeIt] is not decoration: a refusal nobody can act on is one a team learns to route around. */
    data class CannotTell(val why: String, val wouldChangeIt: String) : Tell
}

/**
 * How one statistic moved between two sets of runs, with the interval a
 * bootstrap over those runs puts around it.
 *
 * [Comparison] is the other question and stays its own type: that one is every
 * step of one run against one other, and says what each step did; this is one
 * statistic over many runs a side, and says how sure of it the runs allow
 * anyone to be.
 */
data class Difference(
    val statistic: Statistic,
    /** The baseline's, off its merged population: nanoseconds for a percentile, a share for goodput. */
    val before: Double,
    /** The same, off this side's. */
    val now: Double,
    val runs: Int,
    val baselineRuns: Int,
    /** How large a change the caller declared worth acting on. */
    val acceptable: Share,
    val machine: Machine,
    val baselineMachine: Machine,
    /** The bootstrap over the runs, or null where [refused] says why there is none. */
    val interval: Spread? = null,
    /** Why no interval could be made of these runs, and what would change that. */
    val refused: Tell.CannotTell? = null,
) {

    /** [now] over [before]: 1.04 is four percent more of whatever was read. */
    val ratio: Double get() = now / before

    /** The interval against the threshold this comparison was asked for. */
    val verdict: Tell get() = judgedAt(acceptable)

    /**
     * The same runs judged against another threshold, for a caller who declares
     * one later — an assertion in a test, say, where the number a team cares
     * about belongs beside the assertion.
     */
    fun judgedAt(acceptable: Share): Tell = when {
        refused != null -> refused
        interval == null -> error("a difference carries an interval or the reason there is none; this had neither")
        else -> interval.tell(acceptable, statistic.higherIsWorse)
    }

    /** What to distrust this by, or null when one machine measured both sides. */
    val caveat: String?
        get() = if (machine == baselineMachine) {
            null
        } else {
            "measured on $machine and the baseline on $baselineMachine, so this may be the runner"
        }
}

/**
 * How this set of runs differs from [baseline] on [statistic].
 *
 * The ratio is read off each side's merged population and the interval from
 * resampling the runs that made it. Neither substitutes for the other: a
 * population's percentile cannot be averaged out of its runs', and a merged
 * population has nothing left to say about how far its runs landed apart.
 */
fun Runs.against(baseline: Runs, statistic: Statistic, acceptable: Share = NOTHING_DECLARED): Difference {
    val mine = samplesOf(statistic)
    val theirs = baseline.samplesOf(statistic)
    val refused = refusing(baseline, statistic, mine, theirs)

    return Difference(
        statistic = statistic,
        before = theirs.readMerged(statistic),
        now = mine.readMerged(statistic),
        runs = size,
        baselineRuns = baseline.size,
        acceptable = acceptable,
        machine = first.machine,
        baselineMachine = baseline.first.machine,
        interval = if (refused == null && mine != null && theirs != null) bootstrap(mine, theirs, statistic) else null,
        refused = refused,
    )
}

/**
 * Whether this is not worse than [acceptable].
 *
 * A verdict of cannot tell passes unless [orCannotTell] says otherwise. A test
 * that fails on "cannot tell" fails on a noisy Tuesday and gets deleted on the
 * Wednesday, so a team that would rather stop and look asks for it.
 */
fun Difference.notWorseThan(acceptable: Share, orCannotTell: Boolean = false): Boolean =
    when (judgedAt(acceptable)) {
        Tell.Worse -> false
        Tell.Better -> true
        is Tell.CannotTell -> !orCannotTell
    }

/** What these runs said, in the words an assertion fails with. */
fun Difference.explained(acceptable: Share): String {
    val counted = "$runs runs against $baselineRuns" + interval?.let { ", ratio ${it.low} to ${it.high}" }.orEmpty()
    val declared = "the ${acceptable.described} declared acceptable"
    return when (val told = judgedAt(acceptable)) {
        Tell.Worse -> "${statistic.described} is worse than $declared ($counted)"

        Tell.Better -> "${statistic.described} is better than $declared ($counted)"

        is Tell.CannotTell ->
            "${statistic.described} ($counted): cannot tell. ${told.why}. What would change it: ${told.wouldChangeIt}"
    }
}

/** Any difference these runs can resolve is one, until a caller declares a size worth acting on. */
private val NOTHING_DECLARED: Share = Share(0.0)

private fun Spread.tell(acceptable: Share, higherIsWorse: Boolean): Tell {
    val declared = acceptable.percent / HUNDRED
    val worse = if (higherIsWorse) Tell.Worse else Tell.Better
    return when {
        low > 1.0 + declared -> worse

        high < 1.0 - declared -> if (higherIsWorse) Tell.Better else Tell.Worse

        low > 1.0 - declared && high < 1.0 + declared -> Tell.CannotTell(
            "the whole interval is inside the ${acceptable.described} that was declared acceptable",
            "a smaller threshold, if a change this size is worth acting on",
        )

        else -> Tell.CannotTell(
            "the interval spans the ${acceptable.described} that was declared acceptable",
            "more runs would narrow it",
        )
    }
}

private fun Runs.refusing(
    baseline: Runs,
    statistic: Statistic,
    mine: List<Samples>?,
    theirs: List<Samples>?,
): Tell.CannotTell? {
    val unlike = first.plan.unlike(baseline.first.plan)
    return when {
        unlike.isNotEmpty() -> Tell.CannotTell(
            "these runs were not asked to do the same thing as the baseline: ${unlike.joinToString()}",
            "a baseline of the same plan",
        )

        size < ENOUGH || baseline.size < ENOUGH -> Tell.CannotTell(
            "only $size runs here and ${baseline.size} in the baseline, and an interval over fewer than " +
                "$ENOUGH a side is wider than it is worth reading",
            "five runs a side, at least",
        )

        mine == null || theirs == null -> Tell.CannotTell(
            "${statistic.described} is not something every one of these runs ran",
            "a baseline that ran the same steps this did",
        )

        mine.any { statistic.read(it) == null } || theirs.any { statistic.read(it) == null } -> Tell.CannotTell(
            "not every run measured ${statistic.described}",
            "longer runs, or a statistic these have the samples for",
        )

        theirs.readMerged(statistic) == 0.0 -> Tell.CannotTell(
            "the baseline measured no ${statistic.described}, so there is no ratio to take",
            "a baseline that measured it",
        )

        else -> null
    }
}

/**
 * The interval around the ratio, from ten thousand resamples of the runs
 * themselves. A bootstrap rather than a parametric formula: a latency
 * distribution is skewed and often multimodal, which is exactly what a
 * parametric interval would be assuming it is not.
 */
private fun bootstrap(now: List<Samples>, before: List<Samples>, statistic: Statistic): Spread {
    val random = Random(SEED)
    val resampled = Resampler(now) to Resampler(before)
    val ratios = DoubleArray(RESAMPLES) {
        // Neither read can be absent: every run measured this one, and a
        // resample of them holds more samples than any of them did alone.
        val top = statistic.read(resampled.first.resample(random)) ?: 0.0
        val bottom = statistic.read(resampled.second.resample(random)) ?: 0.0
        top / bottom
    }
    ratios.sort()

    return Spread(low = ratios.at(LOW), high = ratios.at(HIGH))
}

/**
 * Every run's counters on one ladder of bucket bounds, so a resample is the
 * addition of a few long arrays rather than the merge of a few histograms ten
 * thousand times over.
 */
private class Resampler(private val samples: List<Samples>) {

    private val bounds: List<Duration> =
        samples.flatMap { it.timing.distribution.map(Bucket::upperBound) }.distinct().sorted()

    // Mutable accumulators inside a builder that freezes them: nothing outside
    // this class ever sees an array of counts.
    private val rows: List<LongArray> = samples.map { sample ->
        LongArray(bounds.size).also { row ->
            sample.timing.distribution.forEach { row[bounds.binarySearch(it.upperBound)] = it.count }
        }
    }

    fun resample(random: Random): Samples {
        val picks = List(samples.size) { random.nextInt(samples.size) }
        val summed = LongArray(bounds.size)
        picks.forEach { pick -> rows[pick].forEachIndexed { at, count -> summed[at] += count } }

        return Samples(
            timing = bounds.mapIndexedNotNull { at, bound ->
                if (summed[at] == 0L) null else Bucket(bound, summed[at])
            }.timing(),
            count = picks.sumOf { samples[it].count },
            failed = picks.sumOf { samples[it].failed },
        )
    }
}

private fun Runs.samplesOf(statistic: Statistic): List<Samples>? =
    each.map { statistic.samplesIn(it) ?: return null }

private fun List<Samples>?.readMerged(statistic: Statistic): Double = this
    ?.let { all -> Samples(all.map { it.timing }.merged(), all.sumOf { it.count }, all.sumOf { it.failed }) }
    ?.let(statistic::read)
    ?: 0.0

private fun DoubleArray.at(quantile: Double): Double = this[((size - 1) * quantile).roundToInt()]

/** Five runs a side. A bootstrap over three values is arithmetic wearing a lab coat. */
private const val ENOUGH = 5

/** Milliseconds over ten runs, and it takes the question out of the reader's mind. */
private const val RESAMPLES = 10_000

/**
 * One seed rather than a parameter. A verdict that changed between two readings
 * of one set of results would not be a verdict, and a seed a caller can turn is
 * a knob for making one say what they wanted.
 */
private const val SEED = 38L

private const val LOW = 0.025
private const val HIGH = 0.975
private const val HUNDRED = 100.0
