package io.github.matthewjones372.kestrel

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One stage of a staged run, and what the run measured while it was running.
 *
 * Read off the timeline rather than recorded: a histogram per stage per step is
 * steps × stages of them on the path this tool tries hardest not to allocate
 * on, and the timeline already holds every second of the run. The cost is
 * width — these are the timeline's coarse buckets, one significant digit rather
 * than two — and [Timing.precision] carries it rather than leaving a reader to
 * assume a step's.
 *
 * @property from the first whole second of the timeline summed here.
 * @property until one past the last, so `until - from` is what was actually
 *   summed.
 * @property planned what the profile asked this stage for, which differs from
 *   `until - from` wherever a boundary fell inside a second.
 */
data class Stage(
    val index: Int,
    val of: Int,
    val profile: InjectionProfile,
    val from: Duration,
    val until: Duration,
    val planned: Duration,
    val serviceTime: Timing,
    val responseTime: Timing,
    val ok: Long,
    val failed: Long,
) {

    val count: Long get() = ok + failed

    /**
     * Whether the seconds summed here are the window the profile asked for.
     *
     * False where a boundary fell inside a second: a second is the finest thing
     * the timeline holds, so it is counted whole in the stage its start falls
     * in rather than split between two.
     */
    val alignedToSeconds: Boolean get() = until - from == planned
}

/**
 * The run split at the boundaries its profile named, or empty where nothing
 * staged it.
 *
 * Empty for a profile that is not `Stages` — one stage would be a table
 * repeating the totals above it — and for a run with no timeline to read them
 * off, which is a version 5 baseline or a result built from samples.
 *
 * A second is counted in the stage its own start falls in. Nothing is
 * interpolated across a boundary: see [Stage.alignedToSeconds].
 */
val RunResult.stages: List<Stage>
    get() {
        if (timeline.isEmpty()) return emptyList()
        val staged = plan.profile?.staged() ?: return emptyList()

        var opened = Duration.ZERO
        return staged.mapIndexed { index, stage ->
            val from = opened
            opened += stage.over
            // The seconds whose own start falls inside this stage's window.
            // Half-open at the top, so a boundary landing exactly on a second
            // opens the next stage rather than closing this one twice.
            val seconds = timeline.indices.filter { it.seconds >= from && it.seconds < opened }
            Stage(
                index = index,
                of = staged.size,
                profile = stage,
                from = (seconds.minOrNull() ?: 0).seconds,
                until = (seconds.maxOrNull()?.plus(1) ?: 0).seconds,
                planned = stage.over,
                serviceTime = seconds.map { timeline[it].serviceTime }.merged(),
                responseTime = seconds.map { timeline[it].responseTime }.merged(),
                ok = seconds.sumOf { timeline[it].ok },
                failed = seconds.sumOf { timeline[it].failed },
            )
        }
    }

/**
 * The stages of a profile, or null where it names none.
 *
 * A randomised shape unwraps to the shape underneath: jitter moves departures
 * within a window, not the windows themselves.
 */
private fun InjectionProfile.staged(): List<InjectionProfile>? = when (this) {
    is InjectionProfile.Stages -> stages

    is InjectionProfile.Randomized -> of.staged()

    is InjectionProfile.ConstantRate,
    is InjectionProfile.RampRate,
    is InjectionProfile.Replay,
    is InjectionProfile.ClosedUsers,
    -> null
}
