package io.github.matthewjones372.kestrel.engine

import io.github.matthewjones372.kestrel.Action
import io.github.matthewjones372.kestrel.Floor
import io.github.matthewjones372.kestrel.Probe
import io.github.matthewjones372.kestrel.Progress
import io.github.matthewjones372.kestrel.StepResult
import io.github.matthewjones372.kestrel.Timing
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.resolutionOf
import io.github.matthewjones372.kestrel.scenario
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What this machine can resolve, measured by running the same step machinery a
 * simulation runs against an action that does nothing.
 *
 * A null step has no socket and no target, so the only thing that moves between
 * repeats is the machine and Kestrel — which is the floor. Reading the floor off
 * a run's own variance instead is cheaper and confounds the target's
 * variability with the machine's, and a genuinely erratic target would then
 * raise its own floor and hide its own regressions.
 *
 * Measured at the median. The median of a null step is the machine's own
 * throughput and moves with it, so a spread of it is a fraction that means
 * something at another scale; the tail of a null step is stalls of a few
 * milliseconds sitting on a p99 of a few milliseconds, and its spread would be
 * a fraction of nothing much. What the tail can move by is [Floor.hiccups],
 * which is an absolute figure because that is how a stall arrives.
 *
 * Bounded by [within], because a calibration is a phase of a run rather than
 * something to leave going.
 */
fun calibrate(within: Duration = BUDGET): Floor {
    val window = within / (WARMUP + REPEATS)
    // Discarded, not counted: the first run in a process pays for class loading
    // and JIT, and this repository's own regression test measured a tenfold
    // difference between it and every run after it.
    // Silent, always. A calibration is one phase of thirty seconds, not
    // twenty-four runs anybody wants announced, and there is no reporter a
    // caller could pass that would make the twenty-four of them worth reading.
    val measured = List(WARMUP + REPEATS) { nothing.at(RATE, over = window).run(Progress.silent) }.drop(WARMUP)
    val repeats = measured.map { it[STEP].responseTime.p50 }
    return Floor(
        resolution = resolutionOf(repeats),
        // The window that stalled most, rather than a merge across all of them:
        // a frozen timing is not a histogram any more, and the worst window is
        // the one a reader is being warned about.
        hiccups = measured.maxBy { it.hiccups.p99 }.hiccups,
        // The middle repeat rather than the fastest or the mean: it is the one
        // reading of the probe that a bad neighbour cannot pull on its own, and
        // another machine's middle repeat is the same thing measured there.
        probe = Probe(repeats.sorted()[repeats.size / 2]),
    )
}

/**
 * The floor of the machine this JVM is on, measured once and kept: it is a
 * property of the machine rather than of a run.
 */
internal fun machineFloor(): Floor = onceOnThisMachine.value

private val onceOnThisMachine: Lazy<Floor> = lazy { declaredFloor() ?: calibrate() }

/**
 * A floor somebody has already measured, read from `-Dkestrel.resolution=0.02`
 * rather than measured again — thirty seconds per JVM is worth skipping on a
 * runner whose spread is known.
 */
internal fun declaredFloor(): Floor? =
    System.getProperty(RESOLUTION_PROPERTY)?.toDoubleOrNull()?.let { Floor(it, Timing.none) }

internal const val RESOLUTION_PROPERTY: String = "kestrel.resolution"

private const val STEP = "null"

private val nothing = scenario("calibration") { exec(STEP, Action { session -> StepResult.Ok(session) }) }

/** Fast enough that a p99 rests on hundreds of samples rather than on ten. */
private val RATE = 5_000.perSecond

private val BUDGET = 30.seconds

private const val REPEATS = 6

private const val WARMUP = 2
