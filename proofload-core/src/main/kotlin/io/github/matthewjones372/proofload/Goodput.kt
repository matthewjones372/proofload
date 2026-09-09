package io.github.matthewjones372.proofload

import kotlin.time.Duration

/**
 * The share of a step's requests that both succeeded and came back inside
 * [under]: good events over total, which is the shape a latency service-level
 * indicator already has.
 *
 * Read off the successes' own distribution over every request the step made, so
 * a failure that was also slow is counted out once rather than twice.
 */
fun StepStats.met(under: Duration, of: Clock = Clock.ResponseTime): Met =
    Samples(ok.timing(of), count, failed.count).met(under)

/**
 * The same share, off counters that may be several runs' added together: the
 * successes' own distribution, over every request the step made.
 */
internal fun Samples.met(under: Duration): Met {
    if (count == 0L) return Met.Absent("nothing was recorded, so no share of it met anything")

    return when (val inside = timing.share(under)) {
        // Every request failed, so none of them was good: measured, not absent.
        is Met.Absent -> Met.Measured(0.0)

        is Met.Measured -> Met.Measured(inside.fraction * (count - failed) / count)
    }
}

/**
 * The same requests as a rate over [over].
 *
 * The window is a parameter here because a step does not carry the plan it ran
 * under; [goodput] on the run reads it from there.
 */
fun StepStats.goodput(under: Duration, over: Duration, of: Clock = Clock.ResponseTime): Rate {
    require(over > Duration.ZERO) { "a rate needs a window, but over was $over" }
    return (metRequests(under, of) / over.seconds()).perSecond
}

/**
 * Every step's good requests over the window the plan asked for, or nothing
 * when it asked for none.
 *
 * The planned window rather than the span the run observed. It is a real fork —
 * the observed span is the closer account of what happened — and the planned
 * window won it because it is what makes two runs comparable, and a run that
 * did not keep to its window is already flagged by [fellBehind].
 */
fun RunResult.goodput(under: Duration, of: Clock = Clock.ResponseTime): Rate? {
    val window = plan.plannedWindow
    if (window <= Duration.ZERO) return null

    return (steps.values.sumOf { it.metRequests(under, of) } / window.seconds()).perSecond
}

// A count rather than a share, because a rate is a count over a window.
private fun StepStats.metRequests(under: Duration, clock: Clock): Double = when (val met = met(under, clock)) {
    is Met.Absent -> 0.0
    is Met.Measured -> met.fraction * count
}

/** Which of a step's two clocks [clock] names. */
internal fun StepStats.timing(clock: Clock): Timing = when (clock) {
    Clock.ServiceTime -> serviceTime
    Clock.ResponseTime -> responseTime
}

/** The same choice on one side of a step. */
internal fun Outcome.timing(clock: Clock): Timing = when (clock) {
    Clock.ServiceTime -> serviceTime
    Clock.ResponseTime -> responseTime
}

private fun Duration.seconds(): Double = inWholeNanoseconds / NANOS_PER_SECOND

private const val NANOS_PER_SECOND = 1_000_000_000.0
