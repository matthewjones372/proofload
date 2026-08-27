package io.github.matthewjones372.kestrel

import kotlin.math.abs
import kotlin.time.Duration

/**
 * How small a difference the machine a run happened on can see at all, measured
 * on it rather than assumed.
 *
 * A report that states a 3% improvement on a machine that cannot resolve 6% is
 * lying with arithmetic, so [resolution] is the number every claim about a
 * change has to clear first.
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

    /** Whether a difference of [fraction] — 0.03 for 3% — is larger than this machine's own movement. */
    fun resolves(fraction: Double): Boolean = abs(fraction) > resolution

    /**
     * Whether any latency claim made here can mean anything. A machine that
     * moves by [UNUSABLE] of itself between identical runs cannot separate a
     * regression from its own weather, and a report that prints a comparison
     * anyway is asking somebody to act on one.
     */
    val supportsAClaim: Boolean get() = resolution < UNUSABLE

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
