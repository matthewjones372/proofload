package io.github.matthewjones372.kestrel

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Whether a run reached a steady state, and where.
 *
 * Found in the timeline rather than configured. A warm-up setting is a guess,
 * and the estimates written by the developers of the benchmarks they were
 * estimating were out by a median of 28 seconds; a steady state is also not
 * guaranteed to exist, so [NeverSettled] is an answer rather than a failure to
 * find one.
 */
sealed interface SteadyState {

    /** The run settled this far in, counted from its start. */
    data class From(val offset: Duration) : SteadyState

    /** No segment of the run held still, and [why] says which way it was moving. */
    data class NeverSettled(val why: String) : SteadyState

    companion object {

        /**
         * How far two windows may differ and still count as the same one.
         *
         * Twice the precision of the histogram these percentiles are read
         * from, and written as a multiple of it rather than as a number beside
         * it: two readings of one unchanged latency can land a bucket apart,
         * so a tolerance at the width of that bucket would be a detector
         * chasing which bucket a sample fell in.
         *
         * A default, not a discovery. Every report that quotes a verdict from
         * here prints this beside it.
         */
        const val TOLERANCE: Double = 2.0 * Histogram.COARSE_PRECISION

        /** Under this many intervals there is nothing to detect, and a verdict would be a guess. */
        const val LEAST_INTERVALS: Int = 10
    }
}

/**
 * Where this run settled, over every step together: a run settles or it does
 * not, and a step that settles inside one that did not is a curiosity.
 */
val RunResult.steadyState: SteadyState get() = timeline.steadyState()

/**
 * This run over the segment it settled into, and the run itself where it never
 * settled: nothing is discarded by default, and the numbers above stay where
 * they are.
 *
 * What the timeline measured is restricted — the counts, which are exact, and
 * the target's service time, at the timeline's own precision. What a second
 * does not keep is absent rather than carried over from the whole run: there
 * are no response times here, no reason a request failed, and no backlog, and
 * a goal that reads one of those is judged over the whole run instead.
 */
val RunResult.steady: RunResult
    get() = when (val settled = steadyState) {
        is SteadyState.NeverSettled -> this
        is SteadyState.From -> from(settled.offset)
    }

private fun RunResult.from(offset: Duration): RunResult = RunResult(
    startedAt = startedAt.plusSeconds(offset.inWholeSeconds),
    steps = steps.mapValues { (_, step) -> step.from(offset) },
    behind = Timing.none,
    plan = plan,
    arrivals = Arrivals.none,
    machine = machine,
    hiccups = Timing.none,
    timeline = timeline.secondsFrom(offset),
)

private fun StepStats.from(offset: Duration): StepStats {
    val seconds = timeline.secondsFrom(offset)
    val worked = seconds.map { it.okServiceTime }.merged()
    val failed = seconds.map { it.failedServiceTime }.merged()
    return StepStats(
        name = name,
        // No reasons: a second counts what failed and not what the target
        // said about it, so the segment can say how many and not which.
        ok = Outcome(serviceTime = worked, responseTime = Timing.none),
        failed = Outcome(serviceTime = failed, responseTime = Timing.none),
        serviceTime = listOf(worked, failed).merged(),
        responseTime = Timing.none,
        timeline = seconds,
    )
}

/** A step's own seconds can run out before the run's, which is that step having stopped rather than a gap. */
private fun List<Second>.secondsFrom(offset: Duration): List<Second> = drop(offset.inWholeSeconds.toInt())

/**
 * The earliest second after which no later window is materially better than
 * the last one and the tail holds within [SteadyState.TOLERANCE] of its own
 * mean.
 *
 * A pure function of the seconds it is handed, so a verdict is a property of
 * what a run recorded rather than of when it was asked.
 *
 * The signal is each second's p99, which is where a cold JVM shows up and what
 * the report leads with. A second nothing ran in is left out of a window's
 * mean rather than averaged in: a zero second is not a fast second.
 */
fun List<Second>.steadyState(): SteadyState {
    if (isEmpty()) {
        return SteadyState.NeverSettled("nothing was recorded second by second, so there was no shape to look at")
    }
    if (size < SteadyState.LEAST_INTERVALS) {
        return SteadyState.NeverSettled(
            "a run of $size s has nothing to detect: a verdict needs at least " +
                "${SteadyState.LEAST_INTERVALS} intervals",
        )
    }

    val window = size / WINDOWS_IN_A_RUN
    return latencies().levelsOver(window).verdict(window)
}

/** Each second's p99 in nanoseconds, and null for a second nothing ran in. */
private fun List<Second>.latencies(): List<Double?> =
    map { if (it.count == 0L) null else it.p99.inWholeNanoseconds.toDouble() }

/** What each window of the run was worth, indexed by the second it starts at. */
private fun List<Double?>.levelsOver(window: Int): List<Double?> =
    (0..size - window).map { start ->
        val live = subList(start, start + window).filterNotNull()
        if (live.isEmpty()) null else live.average()
    }

private fun List<Double?>.verdict(window: Int): SteadyState {
    val last = last() ?: return SteadyState.NeverSettled("nothing ran in the last $window s, so nothing held")
    val first = first() ?: return SteadyState.NeverSettled("nothing ran in the first $window s, so nothing held")

    // Before the search rather than inside it: a run that ends slower than it
    // began has a flat tail to find, and reporting that as a steady state
    // would bury the finding under an offset.
    if (last > first * (1 + SteadyState.TOLERANCE)) {
        return SteadyState.NeverSettled(
            "the last $window s were ${last.overBy(first)}% slower than the first $window s: " +
                "this run got slower and stayed slower",
        )
    }

    val settled = (0..lastIndex - window).firstOrNull { held(from = it, last = last) }
    return settled?.let { SteadyState.From(it.seconds) } ?: SteadyState.NeverSettled(stillMoving(window, last))
}

/**
 * Whether the run from [from] on is one segment: nothing later materially
 * better than [last] — the improving is over — and every window within
 * tolerance of the tail's own mean, which is what rules out a tail that
 * wandered either way.
 */
private fun List<Double?>.held(from: Int, last: Double): Boolean {
    val tail = drop(from)
    if (tail.any { it == null }) return false

    val levels = tail.filterNotNull()
    val mean = levels.average()
    return levels.all { it >= last * (1 - SteadyState.TOLERANCE) && abs(it - mean) <= mean * SteadyState.TOLERANCE }
}

private fun List<Double?>.stillMoving(window: Int, last: Double): String {
    val before = this[lastIndex - window] ?: return "no $window s of this run held still to the end of it"
    val direction = if (last < before) "faster" else "slower"
    return "the last $window s were still ${last.overBy(before)}% $direction than the $window s before them"
}

/** How far this is from [other], as a share of it, rounded for a sentence to carry. */
private fun Double.overBy(other: Double): Long = abs((this - other) / other * HUNDRED).roundToLong()

// Four windows, so the search can name any offset in the first half of a run
// and still have two windows left to weigh against each other after it.
private const val WINDOWS_IN_A_RUN = 4

private const val HUNDRED = 100.0
