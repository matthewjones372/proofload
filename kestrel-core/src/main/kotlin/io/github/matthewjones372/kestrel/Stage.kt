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

/**
 * This run over one stage's own seconds, so a goal can be asked of the stage it
 * was asked about.
 *
 * Narrowed the way `RunResult.steady` narrows: the counts, which are exact, and
 * both clocks at the timeline's own precision. What a second does not keep is
 * absent rather than carried over from the whole run — there is no reason a
 * request failed here, and no arrivals.
 *
 * The lateness is the exception, and the reason this is not `steady`'s
 * function: `latePerSecond` holds it second by second, so a stage keeps its own
 * backlog and `KeptSchedule` can be asked of a ramp separately from the hold
 * before it. A run that held its schedule flat and lost it climbing is a run
 * whose ramp found the ceiling, and the aggregate answer hides exactly that.
 */
internal fun RunResult.during(stage: Stage): RunResult {
    val from = stage.from.inWholeSeconds.toInt()
    val until = stage.until.inWholeSeconds.toInt()
    return RunResult(
        startedAt = startedAt.plusSeconds(stage.from.inWholeSeconds),
        steps = steps.mapValues { (_, step) -> step.during(from, until) },
        behind = latePerSecond.window(from, until).merged(),
        plan = plan,
        arrivals = Arrivals.none,
        machine = machine,
        hiccups = Timing.none,
        timeline = timeline.window(from, until),
        latePerSecond = latePerSecond.window(from, until),
        usersInFlight = usersInFlight.window(from, until),
    )
}

private fun StepStats.during(from: Int, until: Int): StepStats {
    val seconds = timeline.window(from, until)
    val worked = seconds.map { it.okServiceTime }.merged()
    val failed = seconds.map { it.failedServiceTime }.merged()
    val workedResponse = seconds.map { it.okResponseTime }.merged()
    val failedResponse = seconds.map { it.failedResponseTime }.merged()
    return StepStats(
        name = name,
        // No reasons and no reaches, for the reason a steady segment drops
        // them: a second counts what failed and not what the target said about
        // it, and a user reached this step once, in a second this window may
        // not hold.
        ok = Outcome(serviceTime = worked, responseTime = workedResponse),
        failed = Outcome(serviceTime = failed, responseTime = failedResponse),
        serviceTime = listOf(worked, failed).merged(),
        responseTime = listOf(workedResponse, failedResponse).merged(),
        timeline = seconds,
    )
}

/** A step's own seconds can run out before the run's, which is that step having stopped rather than a gap. */
private fun <T> List<T>.window(from: Int, until: Int): List<T> =
    if (from >= size) emptyList() else subList(from, minOf(until, size)).toList()
