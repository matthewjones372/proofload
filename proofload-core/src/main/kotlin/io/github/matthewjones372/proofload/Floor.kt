package io.github.matthewjones372.proofload

import kotlin.math.abs
import kotlin.time.Duration

/**
 * How small a difference the machine a run happened on can see at all, measured
 * on it rather than assumed.
 *
 * A report that states a 3% improvement on a machine that cannot resolve 6% is
 * lying with arithmetic, so [absolute] is the number every claim about a change
 * has to clear first. In duration rather than as a fraction, because that is
 * the form the movement transfers in: a null step whose median goes from 50µs
 * to 110µs reports 120%, and a target at 250 ms on the same machine in the same
 * second moved by the same 60µs.
 */
data class Floor(
    /** The spread between repeats of one unchanging thing, as a fraction of it: 0.061 is 6.1%. */
    val resolution: Double,
    /** What the injector's own JVM stalled for while the floor was being measured. */
    val hiccups: Timing,

    /**
     * What the repeats measured, where they were measured rather than declared.
     *
     * The same measurement as [resolution] and a different question of it:
     * [resolution] asks how small a difference this machine can see, and this
     * asks whether it is the machine a baseline was taken on. Absent for a
     * floor somebody named instead of measuring, which has no probe behind it.
     */
    val probe: Probe? = null,
) {

    /**
     * How far a repeat of one measurement moved here, in duration.
     *
     * Recovered from the calibration that is already there rather than measured
     * again: [resolution] is this spread divided by the middle repeat and
     * [probe] is that middle repeat, so this multiplies the division back out.
     * Zero for a floor somebody named instead of measuring, which carries a
     * fraction and no magnitude to read it against.
     */
    val absolute: Duration get() = probe?.let { it.took * resolution } ?: Duration.ZERO

    /**
     * Whether a difference of [fraction] — 0.03 for 3% — read off a statistic
     * of magnitude [of] is larger than this machine's own movement.
     *
     * The magnitude is an argument because the fraction alone cannot answer it.
     * The same movement is the whole of a null step and none of a quarter-second
     * target, and only the magnitude the claim is being made at says which of
     * those is being asked.
     */
    fun resolves(fraction: Double, of: Duration): Boolean = of * abs(fraction) > movementAt(of)

    /** The same question read at the magnitude this floor was itself measured at. */
    fun resolves(fraction: Double): Boolean = abs(fraction) > resolution

    /**
     * Whether a claim at the magnitude this floor was measured at can mean
     * anything. [supports] is what a comparison asks, at the magnitude its own
     * claim is made at; this is that question at the null step's.
     */
    val supportsAClaim: Boolean get() = resolution < UNUSABLE

    /**
     * Whether a claim at [magnitude] can mean anything here. A machine that
     * moves by [UNUSABLE] of what is being claimed cannot separate a regression
     * from its own weather, and a comparison printed under that is one somebody
     * goes and acts on.
     *
     * Per claim rather than per machine: the movement that swamps a
     * hundred-microsecond step is invisible at a quarter of a second, and both
     * are claims about the same machine.
     */
    fun supports(magnitude: Duration): Boolean = movementAt(magnitude) < magnitude * UNUSABLE

    /**
     * What this machine moved by, read against a claim at [magnitude].
     *
     * A measured floor knows the magnitude its own repeats were taken at, so it
     * moved by [absolute] wherever the claim is. A floor somebody declared is a
     * fraction and nothing else — it was chosen against the claims that reader
     * makes, so it is applied to theirs as given rather than re-based to a
     * magnitude nobody wrote down.
     */
    fun movementAt(magnitude: Duration): Duration =
        if (probe == null) magnitude * resolution else absolute

    /**
     * Whether the move from [before] to [now] is larger than everything this
     * machine did on its own while the floor was being taken.
     *
     * Two gates because a calibration takes two absolute figures and neither
     * contains the other: [absolute] is how far the measuring machinery's own
     * repeats landed apart, and [hiccups] is what its JVM lost to the rest of
     * the machine. Neither watched a target, so a target's own run-to-run drift
     * — its scheduling, its JIT — is outside both, and a caller that needs that
     * bounded needs repeats of the target rather than repeats of a null step.
     */
    fun separates(before: Duration, now: Duration): Boolean {
        val moved = (now - before).absoluteValue
        return moved > movementAt(before) && moved > hiccups.p99
    }

    companion object {
        /** Where a floor stops bounding a claim and starts replacing it. */
        const val UNUSABLE: Double = 0.40
    }
}

/**
 * The spread of repeated measurements of one unchanging thing, as a fraction of
 * the middle one.
 *
 * The whole range rather than a deviation from a mean: the question is how far
 * apart two runs can land, and both ends of the range are runs that happened.
 */
fun resolutionOf(measurements: List<Duration>): Double {
    require(measurements.size > 1) {
        "a floor is the spread between repeats, so it needs at least two, but had ${measurements.size}"
    }
    val sorted = measurements.map { it.inWholeNanoseconds.toDouble() }.sorted()
    val middle = sorted[sorted.size / 2]
    require(middle > 0.0) { "a floor is a fraction of what was measured, and these measured nothing" }

    return (sorted.last() - sorted.first()) / middle
}
