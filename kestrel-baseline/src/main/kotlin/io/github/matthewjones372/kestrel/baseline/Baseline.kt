package io.github.matthewjones372.kestrel.baseline

import io.github.matthewjones372.kestrel.ArrivalSeries
import io.github.matthewjones372.kestrel.Bucket
import io.github.matthewjones372.kestrel.Histogram
import io.github.matthewjones372.kestrel.InjectionProfile
import io.github.matthewjones372.kestrel.Machine
import io.github.matthewjones372.kestrel.Outcome
import io.github.matthewjones372.kestrel.Plan
import io.github.matthewjones372.kestrel.PlannedArm
import io.github.matthewjones372.kestrel.Probe
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.Shard
import io.github.matthewjones372.kestrel.StepStats
import io.github.matthewjones372.kestrel.Timing
import io.github.matthewjones372.kestrel.WarmUp
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * A run, kept so the next one can be compared to it.
 *
 * The plan and the machine travel with the numbers, because a delta only means
 * anything once the two runs are known to have been asked for the same work.
 *
 * The buckets travel rather than the percentiles: an interval cannot be
 * recomputed from five numbers, and a baseline that cannot be compared honestly
 * is not worth keeping.
 *
 * The format is lines of tab-separated fields, written and read here. A format
 * that needed a parser would be a dependency in disguise, on the classpath of
 * everyone who wanted to compare two runs.
 */
fun RunResult.writeBaseline(path: Path): Path {
    Files.createDirectories(path.toAbsolutePath().parent)
    return Files.writeString(path, asBaseline())
}

internal fun RunResult.asBaseline(): String =
    (
        listOf("$MARKER\t$VERSION", "run\t$startedAt") +
            machine.lines() +
            probe.lines() +
            shard.lines() +
            plan.lines() +
            behind.lines("behind") +
            hiccups.lines("stalls") +
            steps.values.flatMap { it.lines() }
        ).joinToString(separator = "\n", postfix = "\n")

private fun Machine.lines(): List<String> =
    listOf("machine\t$cores\t${jdk.escaped()}\t${os.escaped()}\t${arch.escaped()}")

// Beside the machine rather than beside the steps: it describes what measured
// the run, and a run nobody calibrated writes no line at all rather than a zero
// a later comparison would divide by.
private fun Probe?.lines(): List<String> =
    if (this == null) emptyList() else listOf("probe\t${took.inWholeNanoseconds}")

/**
 * Which injector wrote this, absent where nobody sharded the run.
 *
 * The instant is written too: it is what every injector in the set was given,
 * so a merge can say how far apart they actually started without asking each
 * file to agree about a clock none of them shared.
 */
private fun Shard?.lines(): List<String> =
    if (this == null) emptyList() else listOf("shard\t$index\t$of\t${startingAt.toEpochMilli()}")

private fun Plan.lines(): List<String> =
    listOf("plan\t${scenario.escaped()}") +
        // A warmed run and a cold one did not measure the same thing, and a
        // comparison refuses to pool them — so a file that did not carry this
        // would refuse every warmed run against every baseline ever written.
        // Absent where nothing was warmed, as the probe line is.
        warmUp?.let { listOf("warmup\t${it.over.inWholeNanoseconds}") }.orEmpty() +
        steps.map { "planned\t${it.escaped()}" } +
        profile.postfix().map { "profile\t$it" }

/**
 * The rate line, deepest shape first, so reading it back is a fold onto a stack
 * rather than a parser for a nested format.
 */
private fun InjectionProfile?.postfix(): List<String> = when (this) {
    null -> emptyList()

    // The population and the window, which is the whole of what a closed
    // profile asked for: it named no rate and no offsets.
    is InjectionProfile.ClosedUsers -> listOf("closed\t$count\t${over.inWholeNanoseconds}")

    is InjectionProfile.ConstantRate -> listOf("constant\t$perSecond\t${over.inWholeNanoseconds}")

    is InjectionProfile.RampRate -> listOf("ramp\t$from\t$to\t${over.inWholeNanoseconds}")

    is InjectionProfile.Stages -> stages.flatMap { it.postfix() } + "stages\t${stages.size}"

    is InjectionProfile.Randomized -> of.postfix() + "random\t$seed"

    // The capture's identity rather than the capture: a baseline exists to be
    // compared against, and a million timestamps in a file nobody reads is a
    // format that carries the data twice. Source, count, span and digest are
    // enough to refuse two different captures by name.
    is InjectionProfile.Replay -> listOf(
        "replay\t${series.source.escaped()}\t${series.count}\t${series.span.inWholeNanoseconds}\t" +
            "${series.digest}\t${from.inWholeNanoseconds}\t" +
            "${window?.inWholeNanoseconds ?: -1}\t$scaled",
    )
}

// The whole-step timings are not written: they are the merge of the two sides,
// and a file carrying all three could come back holding a step whose parts do
// not add up to it.
private fun StepStats.lines(): List<String> =
    listOf("step\t${name.escaped()}") + ok.lines("ok", name) + failed.lines("failed", name)

private fun Outcome.lines(side: String, step: String): List<String> =
    serviceTime.lines("$side-service", step) + responseTime.lines("$side-response", step)

/**
 * A run-level table: the same buckets, with no step to name them under.
 *
 * Written as buckets rather than as percentiles for the reason the steps are:
 * a merge adds counts and reads the percentile off the sum, and four
 * percentiles averaged are nobody's measurement.
 */
private fun Timing.lines(kind: String): List<String> =
    distribution.map { bucket -> "$kind\t${bucket.upperBound.inWholeNanoseconds}\t${bucket.count}" }

private fun Timing.lines(clock: String, step: String): List<String> =
    distribution.map { bucket -> "$clock\t${step.escaped()}\t${bucket.upperBound.inWholeNanoseconds}\t${bucket.count}" }

/** Reads a baseline written by [writeBaseline]. */
fun readBaseline(path: Path): RunResult = parseBaseline(Files.readString(path))

internal fun parseBaseline(text: String): RunResult {
    val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
    val header = lines.firstOrNull()?.split(SEPARATOR).orEmpty()
    require(header.getOrNull(0) == MARKER) { "not a Kestrel baseline: ${lines.firstOrNull()}" }
    // Named rather than merely refused. A version this build cannot read is a
    // Kestrel somewhere else, and which one is the only useful thing to say.
    require(header.getOrNull(1) in READABLE) {
        "this baseline is version ${header.getOrNull(1)}; this Kestrel reads " +
            "${READABLE.joinToString(" and ")} and writes $VERSION"
    }

    val startedAt = lines.first { it.startsWith("run$SEPARATOR") }.split(SEPARATOR)[1]
    val named = lines.filter { it.startsWith("step$SEPARATOR") }.map { it.split(SEPARATOR) }
    val buckets = lines.map { it.split(SEPARATOR) }
        .filter { it[0] in SIDES }
        .groupBy { it[0] to it[1].unescaped() }

    val steps = named.associate { fields ->
        val name = fields[1].unescaped()
        // Reasons are not kept: a baseline exists to answer "did this get
        // slower", and a failure that mattered is in the run's own report.
        val ok = buckets.outcome("ok", name)
        val failed = buckets.outcome("failed", name)
        name to StepStats(
            name = name,
            ok = ok,
            failed = failed,
            serviceTime = merged(ok.serviceTime, failed.serviceTime),
            responseTime = merged(ok.responseTime, failed.responseTime),
        )
    }

    return RunResult(
        startedAt = Instant.parse(startedAt),
        steps = steps,
        behind = lines.runLevel("behind"),
        hiccups = lines.runLevel("stalls"),
        plan = lines.asPlan(),
        machine = lines.asMachine(),
        probe = lines.asProbe(),
        shard = lines.asShard(),
    )
}

/** Empty in a version 5 file, which wrote neither table, and in a result built from samples. */
private fun List<String>.runLevel(kind: String): Timing =
    filter { it.startsWith("$kind$SEPARATOR") }
        .map { it.split(SEPARATOR) }
        .map { (_, bound, seen) -> Bucket(bound.toLong().nanoseconds, seen.toLong()) }
        .frozen()

/** Absent in a version 5 file, and in any run nobody split across injectors. */
private fun List<String>.asShard(): Shard? =
    firstOrNull { it.startsWith("shard$SEPARATOR") }
        ?.split(SEPARATOR)
        ?.let { (_, index, of, startingAt) ->
            Shard(index = index.toInt(), of = of.toInt(), startingAt = Instant.ofEpochMilli(startingAt.toLong()))
        }

/** Absent in a version 3 file, and in any run whose machine was never calibrated. */
private fun List<String>.asProbe(): Probe? =
    firstOrNull { it.startsWith("probe$SEPARATOR") }
        ?.let { Probe(it.split(SEPARATOR)[1].toLong().nanoseconds) }

private fun List<String>.asMachine(): Machine {
    val (_, cores, jdk, os, arch) = first { it.startsWith("machine$SEPARATOR") }.split(SEPARATOR)
    return Machine(cores = cores.toInt(), jdk = jdk.unescaped(), os = os.unescaped(), arch = arch.unescaped())
}

// Goals are not kept: a threshold is what a team wanted of the numbers rather
// than work sent at the target, and it is not part of what makes two runs
// comparable.
private fun List<String>.asPlan(): Plan = Plan(
    arms = listOf(
        PlannedArm(
            scenario = first { it.startsWith("plan$SEPARATOR") }.split(SEPARATOR)[1].unescaped(),
            steps = filter { it.startsWith("planned$SEPARATOR") }.map { it.split(SEPARATOR)[1].unescaped() },
            profile = filter { it.startsWith("profile$SEPARATOR") }.map { it.split(SEPARATOR) }.asProfile(),
        ),
    ),
    warmUp = asWarmUp(),
)

/** Absent in a version 4 file, and in any run that warmed nothing. */
private fun List<String>.asWarmUp(): WarmUp? =
    firstOrNull { it.startsWith("warmup$SEPARATOR") }
        ?.let { WarmUp(it.split(SEPARATOR)[1].toLong().nanoseconds) }

private fun List<List<String>>.asProfile(): InjectionProfile? =
    fold(emptyList<InjectionProfile>()) { stack, fields ->
        when (fields[1]) {
            "constant" -> {
                val (_, _, rate, over) = fields
                stack + InjectionProfile.ConstantRate(rate.toDouble(), over.toLong().nanoseconds)
            }

            "ramp" -> {
                val (_, _, from, to, over) = fields
                stack + InjectionProfile.RampRate(from.toDouble(), to.toDouble(), over.toLong().nanoseconds)
            }

            "stages" -> {
                val taken = fields[2].toInt()
                stack.dropLast(taken) + InjectionProfile.Stages(stack.takeLast(taken))
            }

            "random" -> stack.dropLast(1) + InjectionProfile.Randomized(stack.last(), fields[2].toLong())

            "closed" -> {
                val (_, _, count, over) = fields
                stack + InjectionProfile.ClosedUsers(count.toInt(), over.toLong().nanoseconds)
            }

            // What the capture was, not what it held: enough to compare a run
            // against its own baseline and to refuse one replayed from another
            // capture, without the file carrying a million timestamps.
            "replay" -> stack + InjectionProfile.Replay(
                series = ArrivalSeries.recalled(
                    source = fields[2].unescaped(),
                    count = fields[3].toInt(),
                    span = fields[4].toLong().nanoseconds,
                    digest = fields[5].toInt(),
                ),
                from = fields[6].toLong().nanoseconds,
                window = fields[7].toLong().takeIf { it >= 0 }?.nanoseconds,
                scaled = fields[8].toDouble(),
            )

            else -> throw IllegalArgumentException("not a rate line this version knows: ${fields.joinToString()}")
        }
    }.lastOrNull()

private fun Map<Pair<String, String>, List<List<String>>>.outcome(side: String, step: String): Outcome = Outcome(
    serviceTime = get("$side-service" to step).orEmpty().asTiming(),
    responseTime = get("$side-response" to step).orEmpty().asTiming(),
)

private fun List<List<String>>.asTiming(): Timing =
    map { (_, _, bound, seen) -> Bucket(bound.toLong().nanoseconds, seen.toLong()) }.frozen()

/** The whole step, rebuilt from the two sides it was written as. */
private fun merged(left: Timing, right: Timing): Timing =
    (left.distribution + right.distribution)
        .groupBy { it.upperBound }
        .map { (bound, sharing) -> Bucket(bound, sharing.sumOf { it.count }) }
        .sortedBy { it.upperBound }
        .frozen()

/**
 * Buckets from a file, as a timing.
 *
 * Every timing this format has ever carried was counted at full precision —
 * the step timings, the run's lateness and the injector's own stalls, all off
 * a plain `Histogram`; the coarse tables are the timeline and the per-second
 * lateness, and neither is written here. So the width is a fact about the file
 * rather than a guess about it. A format that ever writes a coarse table will
 * have to carry the figure per timing; see 0047's second open question.
 */
private fun List<Bucket>.frozen(): Timing {
    val count = sumOf { it.count }
    if (count == 0L) return Timing.none
    return Timing(
        count = count,
        p50 = at(count, HALF),
        p95 = at(count, NINETY_FIVE),
        p99 = at(count, NINETY_NINE),
        max = lastOrNull()?.upperBound ?: Duration.ZERO,
        distribution = this,
        precision = Histogram.PRECISION,
    )
}

private fun List<Bucket>.at(total: Long, share: Double): Duration {
    if (isEmpty()) return Duration.ZERO
    val wanted = maxOf(1L, Math.ceil(share * total).toLong())
    return asSequence()
        .runningFold(0L to first().upperBound) { (seen, _), bucket -> (seen + bucket.count) to bucket.upperBound }
        .first { (seen, _) -> seen >= wanted }
        .second
}

// A step name is whatever a scenario called it, and a tab in one would split a
// line into the wrong fields.
private fun String.escaped(): String = replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

private fun String.unescaped(): String = replace("\\t", "\t").replace("\\n", "\n").replace("\\\\", "\\")

private val SIDES = setOf("ok-service", "ok-response", "failed-service", "failed-response")

private const val MARKER = "kestrel-baseline"
private const val VERSION = "7"

// Each older version is this one missing a line, so a file written before
// there was one still answers every question a comparison asks of it except
// the one that line carries: 3 has no probe, 4 no warm-up, 5 no lateness,
// stalls or shard, 6 no closed population — which nothing could write, since
// there was no closed model to write one from.
private val READABLE = listOf("3", "4", "5", "6", VERSION)
private const val SEPARATOR = "\t"
private const val HALF = 0.5
private const val NINETY_FIVE = 0.95
private const val NINETY_NINE = 0.99
