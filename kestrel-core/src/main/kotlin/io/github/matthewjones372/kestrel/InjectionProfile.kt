package io.github.matthewjones372.kestrel

import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * When virtual users arrive. Open model only: a profile states departure times
 * up front, so an engine never decides when to send by looking at when the
 * last response came back — which is the shape that makes a tool measure a
 * queue of its own making.
 */
sealed interface InjectionProfile {

    val over: Duration

    data class ConstantRate(val perSecond: Double, override val over: Duration) : InjectionProfile

    data class RampRate(val from: Double, val to: Double, override val over: Duration) : InjectionProfile
}

fun constantRate(rate: Rate, over: Duration): InjectionProfile.ConstantRate {
    requireRate(rate.perSecond, "rate")
    requireWindow(over)
    return InjectionProfile.ConstantRate(rate.perSecond, over)
}

fun rampRate(from: Rate, to: Rate, over: Duration): InjectionProfile.RampRate {
    requireRate(from.perSecond, "from")
    requireRate(to.perSecond, "to")
    requireWindow(over)
    return InjectionProfile.RampRate(from.perSecond, to.perSecond, over)
}

/** How many users the profile describes: the area under its rate line. */
fun InjectionProfile.userCount(): Long = when (this) {
    is InjectionProfile.ConstantRate -> (perSecond * over.seconds()).toLong()
    is InjectionProfile.RampRate -> ((from + to) / 2 * over.seconds()).toLong()
}

/**
 * The offset from the start of the run at which each user departs, in order.
 *
 * Lazy, because a ten-minute run at a thousand a second is six hundred
 * thousand of these and an engine only needs the next one.
 */
fun InjectionProfile.departures(): Sequence<Duration> {
    val count = userCount()
    if (count == 0L) return emptySequence()
    return (0 until count).asSequence().map { index -> departureOf(index) }
}

// Each offset is computed from its own index rather than added to the one
// before it. Accumulating a floating-point interval drifts, and a generator
// that drifts reports the drift as the target's latency.
private fun InjectionProfile.departureOf(index: Long): Duration = when (this) {
    is InjectionProfile.ConstantRate -> atRate(index, perSecond)

    is InjectionProfile.RampRate -> {
        val acceleration = (to - from) / over.seconds()
        if (acceleration == 0.0) {
            atRate(index, from)
        } else {
            // Solve from*t + acceleration*t^2/2 = index for t: the time by
            // which the area under the rate line has delivered `index` users.
            val seconds = (-from + sqrt(from * from + 2 * acceleration * index)) / acceleration
            seconds.toDuration()
        }
    }
}

private fun atRate(index: Long, perSecond: Double): Duration = (index / perSecond).toDuration()

private fun Double.toDuration(): Duration = (this * NANOS_PER_SECOND).toLong().nanoseconds

private fun Duration.seconds(): Double = inWholeNanoseconds / NANOS_PER_SECOND

private fun requireRate(rate: Double, name: String) {
    require(rate.isFinite() && rate >= 0) { "$name must be a finite rate of zero or more, but was $rate" }
}

private fun requireWindow(over: Duration) {
    require(over >= Duration.ZERO) { "over must not be negative, but was $over" }
}

private const val NANOS_PER_SECOND = 1_000_000_000.0
