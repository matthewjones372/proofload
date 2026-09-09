package io.github.matthewjones372.proofload

import kotlin.time.Duration

/**
 * What a fixed, target-free measurement took on the machine a run happened on.
 *
 * A magnitude rather than a spread. [Floor.resolution] is the spread of repeats
 * of this same measurement as a fraction of its own tiny median, so it is a
 * fraction of a different number on every machine and transfers to none of
 * them; two probes are the same work timed twice, and their ratio is a
 * comparison of like with like.
 */
data class Probe(val took: Duration) {

    init {
        require(took > Duration.ZERO) { "a probe that took no time measured nothing to compare: $took" }
    }

    /** How many times slower this machine ran the probe than [other] did: 2.0 is half as fast. */
    fun timesSlowerThan(other: Probe): Double = took / other.took

    /** Whether this machine is slower than [other] by more than [MATERIAL]. */
    fun materiallySlowerThan(other: Probe): Boolean = timesSlowerThan(other) >= MATERIAL

    companion object {
        /**
         * Where a slower probe stops being one machine's weather and starts
         * being a different machine. Repeats on an idle machine land a few
         * percent apart, and the runner classes a team mixes without noticing
         * are further apart than this.
         */
        const val MATERIAL: Double = 1.25
    }
}

/**
 * This run carrying what the probe took on the machine it ran on, so a baseline
 * written from it can be compared against one from somewhere else.
 *
 * Asked for rather than automatic: a calibration is a phase of its own, and a
 * run that measured one without being asked would charge every user for a
 * question only a comparison has.
 */
fun RunResult.calibratedBy(floor: Floor): RunResult = copy(probe = floor.probe)
