package io.github.matthewjones372.kestrel

import kotlin.time.Duration

/**
 * The share of a step's requests that both succeeded and came back inside
 * [under]: good events over total, which is the shape a latency service-level
 * indicator already has.
 *
 * A lower bound rather than an exact count. A histogram counts failed requests
 * beside successful ones, so every request that missed the target is charged
 * against the ones that succeeded — the smallest overlap the two counts allow,
 * and the direction an approximation can be quoted in.
 */
fun StepStats.met(under: Duration, of: Clock = Clock.ResponseTime): Met =
    when (val inside = timing(of).share(under)) {
        is Met.Absent -> inside
        is Met.Measured -> Met.Measured(maxOf(0.0, inside.fraction - failed.toDouble() / count))
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

private fun Duration.seconds(): Double = inWholeNanoseconds / NANOS_PER_SECOND

private const val NANOS_PER_SECOND = 1_000_000_000.0
