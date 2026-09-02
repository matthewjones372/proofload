package io.github.matthewjones372.kestrel

import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

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

    /**
     * [of], with its arrivals drawn from [seed] rather than evenly spaced.
     *
     * A wrapper rather than a fourth kind of rate line: the shape underneath is
     * still the thing that says how many users depart and over how long, so it
     * is still the thing to compare, count and draw.
     */
    data class Randomized(val of: InjectionProfile, val seed: Long) : InjectionProfile {
        override val over: Duration get() = of.over
    }

    /**
     * A window of a real capture, sent at [scaled] times the rate it arrived.
     *
     * A variant rather than a bare sequence of offsets, because every consumer
     * of a profile asks it something: how many users, over how long, drawn
     * from what, described how, written into a baseline as what. A sequence
     * would answer none of those, and each of those `when`s would grow a
     * branch that could not.
     *
     * Scaling is scaling *time*: every gap is divided by [scaled], so twice
     * the rate is the same shape in half the window and the coefficient of
     * variation is exactly unchanged. Thinning the arrivals instead would
     * drive the process towards Poisson — the very shape a replay exists to
     * avoid — and would make the capture's burstiness and the run's
     * incomparable.
     */
    data class Replay(
        val series: ArrivalSeries,
        val from: Duration = Duration.ZERO,
        val window: Duration? = null,
        val scaled: Double = 1.0,
    ) : InjectionProfile {

        init {
            require(scaled > 0.0) { "a replay is sent at a rate above zero, but scaled was $scaled" }
            require(from >= Duration.ZERO) { "a replay starts at or after the capture's own start, not $from" }
            require(window == null || window > Duration.ZERO) { "a replay window is longer than nothing" }
        }

        /** The capture's own offsets inside the window asked for, rebased to zero. */
        internal val taken: LongArray
            get() {
                val begins = series.offsets.first() + from.inWholeNanoseconds
                val ends = window?.let { begins + it.inWholeNanoseconds } ?: Long.MAX_VALUE
                val inside = series.offsets.filter { it >= begins && it < ends }
                return LongArray(inside.size) { inside[it] - begins }
            }

        override val over: Duration
            get() = taken.lastOrNull()?.let { (it / scaled).toLong().nanoseconds } ?: Duration.ZERO
    }
}

/**
 * The same shape and the same count, with arrivals drawn from [seed] instead of
 * spaced on the interval.
 *
 * Real session arrivals are close to Poisson, and queueing delay scales with
 * the variability of arrivals rather than only with their mean, so an even
 * generator understates queueing at the rate it claims to be testing.
 *
 * There is no default seed: an unseeded random run is not one anybody can
 * reproduce.
 */
fun InjectionProfile.randomized(seed: Long): InjectionProfile.Randomized = when (this) {
    is InjectionProfile.Randomized -> InjectionProfile.Randomized(of, seed)

    // A capture is an arrival process already; drawing from it would replace
    // the thing being replayed with a model of it.
    is InjectionProfile.Replay -> throw IllegalArgumentException(alreadyAnArrivalProcess(series.source))

    is InjectionProfile.ConstantRate, is InjectionProfile.RampRate, is InjectionProfile.Stages ->
        InjectionProfile.Randomized(this, seed)
}

/**
 * The seeds this shape draws its arrivals from, in the order its stages run.
 *
 * Empty means every arrival is spaced on the interval, which is the fact a
 * report has to print: a run measured under even arrivals is not comparable
 * with the same mean rate in production.
 */
val InjectionProfile.seeds: List<Long>
    get() = when (this) {
        // A replay is drawn from nothing: its arrivals are what happened.
        is InjectionProfile.ConstantRate, is InjectionProfile.RampRate, is InjectionProfile.Replay -> emptyList()

        is InjectionProfile.Stages -> stages.flatMap { it.seeds }

        is InjectionProfile.Randomized -> listOf(seed)
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

    // Not opened up: the stages of a randomised shape are randomised by it, and
    // lifting them out would leave them spaced on the interval again.
    is InjectionProfile.ConstantRate,
    is InjectionProfile.RampRate,
    is InjectionProfile.Randomized,
    is InjectionProfile.Replay,
    -> listOf(this)
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

/**
 * What this shape is running at when it starts.
 *
 * [endRate]'s mirror, and what a warm-up holds: warming a ramp at its peak
 * would leave the measurement inheriting a state the run never climbed to,
 * while its opening rate is the load the first measured departures meet.
 */
val InjectionProfile.startRate: Rate
    get() = when (this) {
        is InjectionProfile.ConstantRate -> perSecond.perSecond

        is InjectionProfile.RampRate -> from.perSecond

        is InjectionProfile.Stages -> stages.firstOrNull()?.startRate ?: 0.perSecond

        is InjectionProfile.Randomized -> of.startRate

        // The mean over the window, which is the honest single number for a
        // shape that has no one rate.
        is InjectionProfile.Replay -> meanRate()
    }

/** What this shape is running at when it finishes. */
val InjectionProfile.endRate: Rate
    get() = when (this) {
        is InjectionProfile.ConstantRate -> perSecond.perSecond
        is InjectionProfile.RampRate -> to.perSecond
        is InjectionProfile.Stages -> stages.lastOrNull()?.endRate ?: 0.perSecond
        is InjectionProfile.Randomized -> of.endRate
        is InjectionProfile.Replay -> meanRate()
    }

/** A replay's users over its window: no single rate describes it, and this is the mean. */
private fun InjectionProfile.Replay.meanRate(): Rate =
    if (over <= Duration.ZERO) 0.perSecond else (userCount() / over.seconds()).perSecond

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
    is InjectionProfile.ConstantRate -> usersBy(over)
    is InjectionProfile.RampRate -> usersBy(over)
    is InjectionProfile.Stages -> stages.sumOf { it.userCount() }
    is InjectionProfile.Randomized -> of.userCount()
    is InjectionProfile.Replay -> taken.size.toLong()
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
        val acceleration = acceleration()
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

    is InjectionProfile.Randomized -> of.drawnWith(seed)

    // Divided rather than resampled: at one the departures are the capture's
    // gaps nanosecond for nanosecond.
    is InjectionProfile.Replay -> taken.asSequence().map { (it / scaled).toLong().nanoseconds }
}

/**
 * Where the arrivals of [this] fall once they are drawn rather than spaced.
 *
 * Seeded per stage by the seed and the stage's index, so a ramp followed by a
 * hold does not repeat the same draws and the whole shape stays a pure function
 * of the one seed the caller gave.
 */
private fun InjectionProfile.drawnWith(seed: Long): Sequence<Duration> = when (this) {
    is InjectionProfile.Stages ->
        InjectionProfile.Stages(stages.mapIndexed { index, stage -> stage.randomized(seed + index) }).departures()

    is InjectionProfile.ConstantRate -> drawnAcross(over, seed) { at -> usersBy(at) }

    is InjectionProfile.RampRate -> drawnAcross(over, seed) { at -> usersBy(at) }

    is InjectionProfile.Randomized -> of.drawnWith(seed)

    // A capture is already an arrival process. Drawing one from it would
    // replace the thing being replayed with a model of it.
    is InjectionProfile.Replay -> throw IllegalArgumentException(alreadyAnArrivalProcess(series.source))
}

/**
 * A Poisson process conditioned on N arrivals in a window puts them exactly
 * where N sorted uniforms would be. Each window is handed the count [usersBy]
 * says the rate line owes it, so the total stays exact, every arrival stays
 * inside the window it was drawn for, and the shape is still followed rather
 * than flattened.
 *
 * Accumulating exponential gaps is the other construction. It drifts, it needs
 * an accumulator, and it lets the last arrival fall outside the window the
 * profile promised.
 *
 * A window at a time so this stays lazy: a ten-minute run at a thousand a
 * second must not sort six hundred thousand doubles to answer its first
 * departure.
 */
private fun drawnAcross(over: Duration, seed: Long, usersBy: (Duration) -> Long): Sequence<Duration> =
    (0 until ceil(over / ARRIVAL_WINDOW).toInt()).asSequence().flatMap { window ->
        val from = ARRIVAL_WINDOW * window
        val until = minOf(from + ARRIVAL_WINDOW, over)
        drawnIn(
            from = from,
            until = until,
            count = (usersBy(until) - usersBy(from)).toInt(),
            random = Random(seed + window * WINDOW_STRIDE),
        )
    }

private fun drawnIn(from: Duration, until: Duration, count: Int, random: Random): List<Duration> {
    val span = (until - from).inWholeNanoseconds
    val drawn = DoubleArray(count) { random.nextDouble() }
    drawn.sort()
    return drawn.map { (from.inWholeNanoseconds + (it * span).toLong()).nanoseconds }
}

/** How many users the rate line has delivered by [at]: the area under it up to there. */
private fun InjectionProfile.ConstantRate.usersBy(at: Duration): Long = (perSecond * at.seconds()).toLong()

private fun InjectionProfile.RampRate.usersBy(at: Duration): Long =
    at.seconds().let { seconds -> (from * seconds + acceleration() * seconds * seconds / 2).toLong() }

/** How fast the rate line climbs, per second per second. */
private fun InjectionProfile.RampRate.acceleration(): Double = (to - from) / over.seconds()

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

/**
 * The window a draw is conditioned on. A second, because that is the unit a
 * rate is quoted in: each second gets the arrivals its rate asked for, so a
 * ramp still ramps and the sort is a second's worth rather than a run's.
 */
internal val ARRIVAL_WINDOW: Duration = 1.seconds

// Stage seeds step by one and window seeds step by this, so no window of one
// stage can be handed the seed of a window of another.
private const val WINDOW_STRIDE = 2_654_435_761L

/**
 * The capture, or a window of it, sent at [scaled] times the rate it arrived.
 *
 * [from] and [window] cut the capture before the scaling, so they are read in
 * the capture's own time: forty minutes into an hour, ten minutes long, at
 * twice the rate, is five minutes of run.
 */
fun ArrivalSeries.replaying(
    from: Duration = Duration.ZERO,
    window: Duration? = null,
    scaled: Double = 1.0,
): InjectionProfile.Replay = InjectionProfile.Replay(this, from, window, scaled)

private fun alreadyAnArrivalProcess(source: String): String =
    "a replay of $source is already an arrival process; drawing from it would replace what happened with a model"
