package io.github.matthewjones372.kestrel

import kotlin.time.Duration

/**
 * What to do about a verdict, where there is anything to do.
 *
 * A verdict says what happened and leaves the reader to work out what that
 * means for them, which is fine for a person looking at a page and useless to
 * a program: a caller acting on a run needs a sentence, not a margin. Null
 * where a goal was met, because a suggestion attached to a tick is noise.
 *
 * No sentence here names a number nothing measured. "Try 38 a second" would be
 * an estimate printed as advice, which is the same lie as an interpolated
 * percentile in a different grammar.
 */
val Verdict.remedy: String?
    get() = when {
        // `wouldChangeIt` exists for exactly this and is already written; a
        // second sentence beside it would be two answers to one question.
        refused != null -> refused.wouldChangeIt

        met -> null

        else -> goal.shortfall()
    }

private fun Goal.shortfall(): String = when (this) {
    is Goal.PercentileUnder ->
        "${step.name} was slower than $limit at $percentile. Look at ${step.name}'s distribution " +
            "and its failures before changing the load: a tail is usually one of the two."

    is Goal.FailureRateUnder ->
        "${step?.name ?: "The run"} failed more often than $share allows. The failures are grouped by " +
            "reason, and a reason with a status in it is the target's answer rather than a timeout."

    is Goal.GoodputAtLeast ->
        "${step.name} met its target on too few requests. Goodput counts only what was both fast and " +
            "successful, so check whether the shortfall is latency or errors."

    is Goal.KeptSchedule ->
        "The generator did not keep its own schedule, so these numbers include a queue it built. " +
            "Re-run at a lower rate, or spread the load across more injectors."

    is Goal.InEveryStage -> of.shortfall() + " This was asked of every stage, so one stage failing it is enough."
}

/**
 * What to do about a run whose generator lost its own schedule, or null where
 * it kept it.
 *
 * Separate from [Verdict.remedy] because it is true whether or not anybody set
 * a goal: a run that fell behind measured a queue this tool created, and that
 * is worth saying to a caller who asked for nothing.
 */
val RunResult.scheduleRemedy: String?
    get() = when {
        // Two tests, two sentences. One remedy serving both quoted the interval
        // at a reader whose verdict came from the other comparison entirely —
        // evidence arguing against the conclusion it was attached to, which is
        // worse than saying nothing.
        lostGround() ->
            "The generator's own lateness reached ${behind.p99} at p99, past the $ownInterval it " +
                "planned between departures. The load that left is not the load the profile named. Re-run at a " +
                "lower rate, or spread the load across more injectors."

        fellBehind() ->
            "The generator's own lateness reached ${behind.p99} at p99, which is large enough to " +
                "show in a ${slowest()} tail. These latencies carry a queue this tool built. Re-run at a lower " +
                "rate, or spread the load across more injectors."

        else -> null
    }

/** The tail `fellBehind` weighed the lateness against, which is the worst step's. */
private fun RunResult.slowest(): Duration =
    steps.values.maxOfOrNull { it.responseTime.p99 } ?: Duration.ZERO
