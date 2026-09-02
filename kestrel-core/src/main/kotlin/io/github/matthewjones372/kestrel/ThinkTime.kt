package io.github.matthewjones372.kestrel

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * How long a user spends not talking to the target.
 *
 * A constant is the degenerate case rather than a different kind of thing:
 * `pause(2.seconds)` still means what it always did. What it cannot do is
 * model a population — two hundred users that met a constant pause together
 * leave it together, to the scheduler's resolution, and click again in the
 * same instant. No population of readers does that, and the burst it produces
 * is the metronome 0034 removed from the arrivals put back inside the journey.
 *
 * A value rather than a lambda, so a scenario stays something this repository
 * can print, compare and seed: `plan()` can name the distribution, the page can
 * say which was asked for, and a constant can be told from a draw — which is
 * what decides whether a seed is owed.
 */
sealed interface ThinkTime {

    /** What a draw from this would give, on average. */
    val mean: Duration

    /** How long to wait, drawn from [random] where this is a distribution. */
    fun drawnFrom(random: Random): Duration

    /** The same wait every time: what `pause(2.seconds)` means. */
    data class Constant(val duration: Duration) : ThinkTime {

        init {
            require(duration >= Duration.ZERO) { "a pause cannot run backwards, but was $duration" }
        }

        override val mean: Duration get() = duration

        override fun drawnFrom(random: Random): Duration = duration
    }

    /**
     * Memoryless waits about [mean] long: the shape a wait has when what a
     * user does next does not depend on how long they have been at it.
     */
    data class Exponential(override val mean: Duration) : ThinkTime {

        init {
            require(mean > Duration.ZERO) { "an exponential wait needs a mean above zero, but was $mean" }
        }

        // Inverse transform. `nextDouble()` is in [0, 1), so 1 - it is in
        // (0, 1] and the logarithm is defined at every draw.
        override fun drawnFrom(random: Random): Duration =
            (-ln(1.0 - random.nextDouble()) * mean.inWholeNanoseconds).toLong().nanoseconds
    }

    /**
     * Waits whose logarithm is normal: a body around [median] with a long
     * right tail, which is the shape a reading time usually has.
     *
     * [sigma] is the spread of that logarithm, so it is a number rather than a
     * duration — nought point five is a modest tail and one and a half is a
     * heavy one.
     */
    data class Lognormal(val median: Duration, val sigma: Double) : ThinkTime {

        init {
            require(median > Duration.ZERO) { "a lognormal wait needs a median above zero, but was $median" }
            require(sigma > 0.0) { "sigma is the spread of the logarithm and must be above zero, but was $sigma" }
        }

        /** The mean of a lognormal sits above its median by half the variance, exponentiated. */
        override val mean: Duration
            get() = (median.inWholeNanoseconds * exp(sigma * sigma / HALVED)).toLong().nanoseconds

        override fun drawnFrom(random: Random): Duration =
            (median.inWholeNanoseconds * exp(sigma * random.nextGaussian())).toLong().nanoseconds
    }

    /** Anywhere between [from] and [until], evenly: the shape to reach for when nothing is known. */
    data class Uniform(val from: Duration, val until: Duration) : ThinkTime {

        init {
            require(from >= Duration.ZERO) { "a pause cannot run backwards, but was $from" }
            require(until > from) { "a uniform wait needs a range, but was $from to $until" }
        }

        override val mean: Duration get() = (from + until) / HALVED

        override fun drawnFrom(random: Random): Duration =
            random.nextLong(from.inWholeNanoseconds, until.inWholeNanoseconds).nanoseconds
    }
}

/** A wait of exactly this long, for a caller writing one out. */
fun constant(duration: Duration): ThinkTime = ThinkTime.Constant(duration)

fun exponential(mean: Duration): ThinkTime = ThinkTime.Exponential(mean)

fun lognormal(median: Duration, sigma: Double): ThinkTime = ThinkTime.Lognormal(median, sigma)

fun uniform(from: Duration, until: Duration): ThinkTime = ThinkTime.Uniform(from, until)

/**
 * Box-Muller, because `kotlin.random.Random` has no Gaussian of its own and a
 * dependency for one number is a dependency.
 *
 * One of the pair is used and the other dropped: keeping it would need state
 * per user, and this is drawn where a user is already about to park.
 */
private fun Random.nextGaussian(): Double {
    val uniform = 1.0 - nextDouble()
    val angle = FULL_TURN * Math.PI * nextDouble()
    return sqrt(-TWICE * ln(uniform)) * cos(angle)
}

private const val FULL_TURN = 2.0

private const val TWICE = 2.0

/** Two, where a formula halves or doubles: named so the arithmetic reads as arithmetic. */
private const val HALVED = 2.0
