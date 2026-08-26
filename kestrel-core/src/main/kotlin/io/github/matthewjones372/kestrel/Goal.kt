package io.github.matthewjones372.kestrel

import kotlin.time.Duration

/** Which clock a percentile goal reads. */
enum class Clock {
    /** What the target took. */
    ServiceTime,

    /**
     * What a user would have seen: service time plus the wait this tool
     * caused. The default, because a goal written against service time can be
     * met by a generator that never sent the load.
     */
    ResponseTime,
}

/** A share of something, as a percentage. */
@JvmInline
value class Share(val percent: Double)

val Number.percent: Share get() = Share(toDouble())

/**
 * What good looks like for a run, stated once.
 *
 * A value, so the same declaration drives the assertion in a test and the
 * verdict on a page — which makes the two disagreeing impossible rather than
 * merely unlikely.
 */
sealed interface Goal {

    /** What this asks for, in the words a report prints. */
    val described: String

    fun judge(result: RunResult): Verdict

    data class PercentileUnder(
        val step: StepName,
        val percentile: String,
        val clock: Clock,
        val limit: Duration,
    ) : Goal {
        override val described: String get() = "${step.name} $percentile under $limit"

        override fun judge(result: RunResult): Verdict {
            val stats = result.steps[step.name]
                ?: return Verdict.missed(this, Measurement.Absent("the step never ran"))
            val timing = when (clock) {
                Clock.ServiceTime -> stats.serviceTime
                Clock.ResponseTime -> stats.responseTime
            }
            val measured = timing.at(percentile)
            return verdictFor(
                met = measured <= limit,
                measured = measured.inWholeNanoseconds.toDouble(),
                limit = limit.inWholeNanoseconds.toDouble(),
                shown = Measurement.Took(measured),
            )
        }
    }

    data class FailureRateUnder(val step: StepName?, val share: Share) : Goal {
        override val described: String get() =
            "${step?.name ?: "the run"} failing under ${share.percent}%"

        override fun judge(result: RunResult): Verdict {
            val counts = step?.let {
                result.steps[it.name] ?: return Verdict.missed(this, Measurement.Absent("the step never ran"))
            }
            val total = counts?.count ?: result.count
            val failed = counts?.failed ?: result.failed
            val measured = if (total == 0L) 0.0 else failed.toDouble() / total * HUNDRED
            return verdictFor(measured <= share.percent, measured, share.percent, Measurement.Share(measured))
        }
    }

    /** That the generator kept to its own schedule, so the rest of the numbers mean what they say. */
    data object KeptSchedule : Goal {
        override val described: String get() = "the generator keeps its schedule"

        override fun judge(result: RunResult): Verdict =
            if (result.fellBehind()) Verdict.missed(this, Measurement.Took(result.behind.p99))
            else Verdict.met(this, Measurement.Took(result.behind.p99))
    }
}

/**
 * Whether a goal was met, and by what margin.
 *
 * The margin is a share of the target rather than an absolute: "51% over"
 * reads the same whether the target was 200 ms or two seconds, and tells a
 * reader whether they are looking at tuning or at design.
 */
private fun Goal.verdictFor(met: Boolean, measured: Double, limit: Double, shown: Measurement): Verdict {
    val over = if (limit == 0.0) null else (measured - limit) / limit * HUNDRED
    return if (met) Verdict.met(this, shown) else Verdict.missed(this, shown, over)
}

/**
 * What a goal was judged against.
 *
 * A value rather than a rendered string: core has no opinion on how a duration
 * should read, and a report that was handed text could not apply the same
 * formatting it uses everywhere else.
 */
sealed interface Measurement {

    data class Took(val duration: Duration) : Measurement

    data class Share(val percent: Double) : Measurement

    /** Why there was nothing to measure — a step that never ran, say. */
    data class Absent(val because: String) : Measurement
}

/** What became of a goal, and by how much. */
data class Verdict(val goal: Goal, val met: Boolean, val measured: Measurement, val overBy: Double?) {

    companion object {
        internal fun met(goal: Goal, measured: Measurement): Verdict = Verdict(goal, true, measured, null)

        internal fun missed(goal: Goal, measured: Measurement, overBy: Double? = null): Verdict =
            Verdict(goal, false, measured, overBy)
    }
}

/** A percentile of a step, waiting for the limit it has to stay under. */
data class PercentileOf internal constructor(
    private val step: StepName,
    private val percentile: String,
    private val clock: Clock,
) {
    infix fun under(limit: Duration): Goal = Goal.PercentileUnder(step, percentile, clock, limit)
}

fun p50(step: StepName, of: Clock = Clock.ResponseTime): PercentileOf = PercentileOf(step, "p50", of)

fun p95(step: StepName, of: Clock = Clock.ResponseTime): PercentileOf = PercentileOf(step, "p95", of)

fun p99(step: StepName, of: Clock = Clock.ResponseTime): PercentileOf = PercentileOf(step, "p99", of)

/** How much of the run may fail, waiting for the share it has to stay under. */
data class FailuresOf internal constructor(private val step: StepName?) {
    infix fun under(share: Share): Goal = Goal.FailureRateUnder(step, share)
}

val failureRate: FailuresOf get() = FailuresOf(null)

fun failureRate(step: StepName): FailuresOf = FailuresOf(step)

val keptSchedule: Goal get() = Goal.KeptSchedule

internal fun Timing.at(percentile: String): Duration = when (percentile) {
    "p50" -> p50
    "p95" -> p95
    else -> p99
}

private const val HUNDRED = 100.0
