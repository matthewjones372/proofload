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

    /**
     * Stages in order: each departs at its own offsets, shifted by the ones
     * before it. A shape rather than a run, so it can still be compared and
     * counted before anything is sent.
     */
    data class Stages(val stages: List<InjectionProfile>) : InjectionProfile {
        override val over: Duration get() = stages.fold(Duration.ZERO) { total, stage -> total + stage.over }
    }
}

/**
 * This shape, then [next].
 *
 * Flattened rather than nested: two ways of writing one shape have to compare
 * equal, or a profile is only half a value.
 */
infix fun InjectionProfile.then(next: InjectionProfile): InjectionProfile =
    InjectionProfile.Stages(asStages() + next.asStages())

private fun InjectionProfile.asStages(): List<InjectionProfile> = when (this) {
    is InjectionProfile.Stages -> stages
    is InjectionProfile.ConstantRate, is InjectionProfile.RampRate -> listOf(this)
}

/** [constantRate], under the name it reads as in a chain. */
fun hold(rate: Rate, over: Duration): InjectionProfile.ConstantRate = constantRate(rate, over)

/**
 * This shape, then a ramp from whatever it was running at to [rate].
 *
 * The name says it reads the receiver. A bare `rampTo` would start from one
 * rate in a chain and another on its own, which makes a DSL guessable rather
 * than readable.
 */
fun InjectionProfile.thenRampTo(rate: Rate, over: Duration): InjectionProfile =
    then(rampRate(from = endRate, to = rate, over = over))

/** What this shape is running at when it finishes. */
val InjectionProfile.endRate: Rate
    get() = when (this) {
        is InjectionProfile.ConstantRate -> perSecond.perSecond
        is InjectionProfile.RampRate -> to.perSecond
        is InjectionProfile.Stages -> stages.lastOrNull()?.endRate ?: 0.perSecond
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
    is InjectionProfile.Stages -> stages.sumOf { it.userCount() }
}

/**
 * The offset from the start of the run at which each user departs, in order.
 *
 * Lazy, because a ten-minute run at a thousand a second is six hundred
 * thousand of these and an engine only needs the next one.
 */
fun InjectionProfile.departures(): Sequence<Duration> = when (this) {
    is InjectionProfile.Stages ->
        stages
            .runningFold(Duration.ZERO to emptySequence<Duration>()) { (start, _), stage ->
                (start + stage.over) to stage.departures().map { start + it }
            }
            .asSequence()
            .flatMap { (_, departures) -> departures }

    is InjectionProfile.ConstantRate -> indices().map { index -> atRate(index, perSecond) }

    is InjectionProfile.RampRate -> {
        val acceleration = (to - from) / over.seconds()
        if (acceleration == 0.0) {
            indices().map { index -> atRate(index, from) }
        } else {
            // Solve from*t + acceleration*t^2/2 = index for t: the time by
            // which the area under the rate line has delivered `index` users.
            indices().map { index ->
                ((-from + sqrt(from * from + 2 * acceleration * index)) / acceleration).toDuration()
            }
        }
    }
}

private fun InjectionProfile.indices(): Sequence<Long> = (0 until userCount()).asSequence()

// Each offset is computed from its own index rather than added to the one
// before it. Accumulating a floating-point interval drifts, and a generator
// that drifts reports the drift as the target's latency.
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
