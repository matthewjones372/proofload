package io.github.matthewjones372.kestrel.export

import io.github.matthewjones372.kestrel.Arrivals
import io.github.matthewjones372.kestrel.Concurrency
import io.github.matthewjones372.kestrel.Machine
import io.github.matthewjones372.kestrel.Measurement
import io.github.matthewjones372.kestrel.Outcome
import io.github.matthewjones372.kestrel.Plan
import io.github.matthewjones372.kestrel.PlannedArm
import io.github.matthewjones372.kestrel.Probe
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.Second
import io.github.matthewjones372.kestrel.SteadyState
import io.github.matthewjones372.kestrel.StepStats
import io.github.matthewjones372.kestrel.Tail
import io.github.matthewjones372.kestrel.Tell
import io.github.matthewjones372.kestrel.Timing
import io.github.matthewjones372.kestrel.Verdict
import io.github.matthewjones372.kestrel.concurrency
import io.github.matthewjones372.kestrel.fellBehind
import io.github.matthewjones372.kestrel.heldScheduleFor
import io.github.matthewjones372.kestrel.lostGround
import io.github.matthewjones372.kestrel.ownInterval
import io.github.matthewjones372.kestrel.precision
import io.github.matthewjones372.kestrel.remedy
import io.github.matthewjones372.kestrel.scheduleRemedy
import io.github.matthewjones372.kestrel.steadyState
import java.nio.file.Files
import java.nio.file.Path

/** How much of a run a document carries. */
enum class Density {

    /**
     * The answer and nothing else, small enough to hold in a prompt, a comment
     * or a chat message.
     */
    Summary,

    /** Every step, second and distribution the result has. */
    Full,
    ;

    internal val described: String get() = name.lowercase()
}

/**
 * A finished run as JSON, for a reader that is a program.
 *
 * Durations are the nanoseconds the histogram reported. The encoding holds the
 * measurement and the reader does the formatting, so there is one place a digit
 * can be lost.
 */
fun RunResult.json(density: Density = Density.Summary): String = jsonObject(
    depth = 0,
    fields = envelope(density) + when (density) {
        Density.Summary -> summaryFields(density)
        Density.Full -> summaryFields(density) + fullFields()
    },
) + "\n"

/** The same document, on disk. Returns the path, so a caller can hand it straight on. */
fun RunResult.writeJson(path: Path, density: Density = Density.Full): Path =
    Files.writeString(path, json(density), Charsets.UTF_8)

private fun RunResult.envelope(density: Density): List<Pair<String, String>> = listOf(
    // Named before anything measured: a reader that does not know the version
    // has to guess at the rest, and guesses wrong once.
    "schema" to jsonString(SCHEMA),
    "density" to jsonString(density.described),
    "startedAt" to jsonString(startedAt.toString()),
    "durationUnit" to jsonString("nanoseconds"),
    // Read off the timings rather than off the constant the recorder chose: a
    // reader pulling a percentile out of this needs the width of the bucket it
    // actually came from.
    "precision" to (precision?.toString() ?: "null"),
)

private fun RunResult.summaryFields(density: Density): List<Pair<String, String>> = listOf(
    // First, because it is the field most readers want and the only one some
    // of them read.
    "verdict" to jsonString(headline().described),
    // Beside the verdict rather than buried in the goals: a caller acting on
    // one run reads the headline and the sentence under it, and nothing else.
    "remedy" to (headlineRemedy()?.let { jsonString(it) } ?: "null"),
    "plan" to plan.toJson(depth = 1),
    "schedule" to scheduleJson(depth = 1),
    "goals" to judged().jsonArray(depth = 1) { it.toJson(depth = 2, density = density) },
    "steadyState" to steadyState.toJson(depth = 1),
    "concurrency" to concurrency.toJson(depth = 1),
    "probe" to probe.toJson(depth = 1),
    "count" to count.toString(),
    "ok" to ok.toString(),
    "failed" to failed.toString(),
    // Every step's failures folded into one list. A caller asking what went
    // wrong is asking about the run, and the per-step split is in Full.
    "failures" to failuresByReason().jsonArray(depth = 1) { (reason, seen) ->
        jsonObject(depth = 2, fields = listOf("reason" to jsonString(reason), "count" to seen.toString()))
    },
    "arrivals" to arrivals.toJson(depth = 1),
    "machine" to machine.toJson(depth = 1),
)

private fun RunResult.fullFields(): List<Pair<String, String>> = listOf(
    "behind" to behind.toJson(depth = 1),
    "hiccups" to hiccups.toJson(depth = 1),
    "steps" to steps.values.jsonArray(depth = 1) { it.toJson(depth = 2) },
    "timeline" to timeline.jsonArray(depth = 1) { it.toJson(depth = 2) },
    "latePerSecond" to latePerSecond.jsonArray(depth = 1) { it.toJson(depth = 2) },
)

/**
 * Summed across steps rather than concatenated, so a reason seen by three steps
 * is one row with the total a reader would otherwise have to add up.
 */
private fun RunResult.failuresByReason(): List<Pair<String, Long>> =
    steps.values
        .flatMap { step -> step.failed.reasons.entries }
        .groupingBy { it.key.described }
        .fold(0L) { running, entry -> running + entry.value }
        .toList()
        .sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first })

/**
 * What a run concluded, in one word.
 *
 * The order is the claim: a generator that lost its own schedule did not
 * measure the target, so its missed goals describe a queue this tool built and
 * reporting them as the answer is the trap Kestrel exists to close. It still
 * outranks having asked nothing, because a run that kept no schedule is worth
 * saying so about whether or not anyone set it a goal. A definite miss then
 * outranks a refusal, because one is a fact and the other is uncertainty.
 */
private fun RunResult.headline(): Headline {
    val verdicts = judged()
    return when {
        fellBehind() || lostGround() -> Headline.Behind
        verdicts.isEmpty() -> Headline.NothingAsked
        verdicts.any { !it.met } -> Headline.Missed
        verdicts.any { it.refused != null } -> Headline.CannotTell
        else -> Headline.Met
    }
}

private enum class Headline {
    Behind,

    /**
     * A run with no goals on it. Not `Met`: a run that was asked nothing met
     * nothing, and a tick nobody earned is the thing the verdicts exist to
     * stop printing.
     */
    NothingAsked,
    Missed,
    CannotTell,
    Met,
    ;

    val described: String get() = name.replaceFirstChar { it.lowercase() }
}

private fun RunResult.judged(): List<Verdict> = plan.goals.flatMap { it.judgeAll(this) }

private fun RunResult.scheduleJson(depth: Int): String = jsonObject(
    depth = depth,
    fields = listOf(
        "kept" to (!fellBehind() && !lostGround()).toString(),
        // How long it held before the first second that lost ground, which is
        // what says at what point a rate stopped being the rate that left.
        "heldFor" to (heldScheduleFor?.inWholeNanoseconds?.toString() ?: "null"),
        "lostGround" to lostGround().toString(),
        "behindP99" to behind.p99.inWholeNanoseconds.toString(),
        "plannedInterval" to ownInterval.inWholeNanoseconds.toString(),
        "remedy" to (scheduleRemedy?.let { jsonString(it) } ?: "null"),
    ),
)

/**
 * The one sentence a caller should act on, in the order the headline is
 * decided: a schedule this tool failed to keep is the answer whatever else
 * happened, and after that the first goal that definitely missed.
 */
private fun RunResult.headlineRemedy(): String? =
    scheduleRemedy
        ?: judged().firstOrNull { !it.met }?.remedy
        ?: judged().firstOrNull { it.refused != null }?.remedy

/**
 * A summary carries the margin and leaves the sentence to the headline. Six
 * goals with a paragraph each is the two-kilobyte budget spent on one string
 * repeated, and the headline already names the one to act on.
 */
private fun Verdict.toJson(depth: Int, density: Density): String = jsonObject(
    depth = depth,
    fields = listOf(
        "asked" to jsonString(goal.described),
        "met" to met.toString(),
        "measured" to measured.toJson(depth + 1),
        "overBy" to (overBy?.toString() ?: "null"),
        "stage" to (stage?.let { "${it.index + 1}" } ?: "null"),
        "cannotTell" to refused.toJson(depth + 1),
    ) + when (density) {
        Density.Summary -> emptyList()
        Density.Full -> listOf("remedy" to (remedy?.let { jsonString(it) } ?: "null"))
    },
)

/** All three keys always: a reader testing for `took` should not have to know how absent is spelled. */
private fun Measurement.toJson(depth: Int): String {
    val (took, percent, because) = when (this) {
        is Measurement.Took -> Triple(duration.inWholeNanoseconds.toString(), "null", "null")
        is Measurement.Share -> Triple("null", percent.toString(), "null")
        is Measurement.Absent -> Triple("null", "null", jsonString(because))
    }
    return jsonObject(
        depth = depth,
        fields = listOf("took" to took, "percent" to percent, "because" to because),
    )
}

/**
 * `wouldChangeIt` travels with `why`. A refusal nobody can act on is one a
 * caller learns to route around, and that holds for a caller reading JSON.
 */
private fun Tell.CannotTell?.toJson(depth: Int): String = when (this) {
    null -> "null"

    else -> jsonObject(
        depth = depth,
        fields = listOf("why" to jsonString(why), "wouldChangeIt" to jsonString(wouldChangeIt)),
    )
}

private fun SteadyState.toJson(depth: Int): String {
    val (settledAfter, why) = when (this) {
        is SteadyState.From -> offset.inWholeNanoseconds.toString() to "null"
        is SteadyState.NeverSettled -> "null" to jsonString(why)
    }
    return jsonObject(
        depth = depth,
        fields = listOf(
            "tolerance" to SteadyState.TOLERANCE.toString(),
            "settledAfter" to settledAfter,
            "why" to why,
        ),
    )
}

/** L = lambda W, both sides and whether they agree — the run's own arithmetic checked against itself. */
private fun Concurrency.toJson(depth: Int): String = when (this) {
    is Concurrency.Measured -> jsonObject(
        depth = depth,
        fields = listOf(
            "observed" to observed.toString(),
            "fromServiceTime" to fromServiceTime.toString(),
            "fromResponseTime" to fromResponseTime.toString(),
            "ratio" to ratio.toString(),
            "backlog" to backlog.toString(),
            "agrees" to agrees.toString(),
            "samples" to samples.toString(),
            "because" to "null",
        ),
    )

    is Concurrency.Absent -> jsonObject(
        depth = depth,
        fields = listOf(
            "observed" to "null",
            "fromServiceTime" to "null",
            "fromResponseTime" to "null",
            "ratio" to "null",
            "backlog" to "null",
            "agrees" to "null",
            "samples" to "null",
            "because" to jsonString(because),
        ),
    )
}

/** What a fixed, target-free measurement took here, where one was taken: the machine, not the target. */
private fun Probe?.toJson(depth: Int): String = when (this) {
    null -> "null"
    else -> jsonObject(depth = depth, fields = listOf("took" to took.inWholeNanoseconds.toString()))
}

private fun Plan.toJson(depth: Int): String = jsonObject(
    depth = depth,
    fields = listOf("arms" to arms.jsonArray(depth + 1) { it.toJson(depth + 2) }),
)

private fun PlannedArm.toJson(depth: Int): String = jsonObject(
    depth = depth,
    fields = listOf(
        "scenario" to jsonString(scenario),
        "steps" to steps.jsonArray(depth + 1) { jsonString(it) },
        "plannedUsers" to plannedUsers.toString(),
    ),
)

private fun Machine.toJson(depth: Int): String = jsonObject(
    depth = depth,
    fields = listOf(
        "cores" to cores.toString(),
        "jdk" to jsonString(jdk),
        "os" to jsonString(os),
        "arch" to jsonString(arch),
    ),
)

private fun Arrivals.toJson(depth: Int): String = jsonObject(
    depth = depth,
    fields = listOf(
        "count" to count.toString(),
        "mean" to mean.inWholeNanoseconds.toString(),
        "cov" to cov.toString(),
    ),
)

private fun StepStats.toJson(depth: Int): String = jsonObject(
    depth = depth,
    fields = listOf(
        "name" to jsonString(name),
        "count" to count.toString(),
        "reached" to reached.toString(),
        "serviceTime" to serviceTime.toJson(depth + 1),
        "responseTime" to responseTime.toJson(depth + 1),
        "ok" to ok.toJson(depth + 1),
        "failed" to failed.toJson(depth + 1),
    ),
)

private fun Outcome.toJson(depth: Int): String = jsonObject(
    depth = depth,
    fields = listOf(
        "count" to count.toString(),
        "serviceTime" to serviceTime.toJson(depth + 1),
        "responseTime" to responseTime.toJson(depth + 1),
    ),
)

private fun Second.toJson(depth: Int): String = jsonObject(
    depth = depth,
    fields = listOf(
        "count" to count.toString(),
        "ok" to ok.toString(),
        "failed" to failed.toString(),
        "p50" to p50.inWholeNanoseconds.toString(),
        "p99" to p99.inWholeNanoseconds.toString(),
    ),
)

private fun Timing.toJson(depth: Int): String = jsonObject(
    depth = depth,
    fields = listOf(
        "count" to count.toString(),
        "p50" to p50.inWholeNanoseconds.toString(),
        "p95" to p95.inWholeNanoseconds.toString(),
        "p99" to p99.inWholeNanoseconds.toString(),
        // Null rather than a missing key: a timing too thin to have measured
        // its tail still has the field, and `count` beside it says why.
        "p999" to when (val tail = p999) {
            is Tail.Measured -> tail.duration.inWholeNanoseconds.toString()
            is Tail.Absent -> "null"
        },
        "max" to max.inWholeNanoseconds.toString(),
    ),
)

private const val SCHEMA = "kestrel/run/1"
