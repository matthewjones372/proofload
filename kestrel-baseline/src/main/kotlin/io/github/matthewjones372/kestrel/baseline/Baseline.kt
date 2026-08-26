package io.github.matthewjones372.kestrel.baseline

import io.github.matthewjones372.kestrel.Bucket
import io.github.matthewjones372.kestrel.Histogram
import io.github.matthewjones372.kestrel.InjectionProfile
import io.github.matthewjones372.kestrel.Machine
import io.github.matthewjones372.kestrel.Plan
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.StepStats
import io.github.matthewjones372.kestrel.Timing
import io.github.matthewjones372.kestrel.timing
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
            plan.lines() +
            steps.values.flatMap { it.lines() }
        ).joinToString(separator = "\n", postfix = "\n")

private fun Machine.lines(): List<String> =
    listOf("machine\t$cores\t${jdk.escaped()}\t${os.escaped()}\t${arch.escaped()}")

private fun Plan.lines(): List<String> =
    listOf("plan\t${scenario.escaped()}") +
        steps.map { "planned\t${it.escaped()}" } +
        profile.postfix().map { "profile\t$it" }

/**
 * The rate line, deepest shape first, so reading it back is a fold onto a stack
 * rather than a parser for a nested format.
 */
private fun InjectionProfile?.postfix(): List<String> = when (this) {
    null -> emptyList()
    is InjectionProfile.ConstantRate -> listOf("constant\t$perSecond\t${over.inWholeNanoseconds}")
    is InjectionProfile.RampRate -> listOf("ramp\t$from\t$to\t${over.inWholeNanoseconds}")
    is InjectionProfile.Stages -> stages.flatMap { it.postfix() } + "stages\t${stages.size}"
    is InjectionProfile.Randomized -> of.postfix() + "random\t$seed"
}

private fun StepStats.lines(): List<String> =
    listOf("step\t${name.escaped()}\t$count\t$ok") +
        serviceTime.lines("service", name) +
        responseTime.lines("response", name)

private fun Timing.lines(clock: String, step: String): List<String> =
    distribution.map { bucket -> "$clock\t${step.escaped()}\t${bucket.upperBound.inWholeNanoseconds}\t${bucket.count}" }

/** Reads a baseline written by [writeBaseline]. */
fun readBaseline(path: Path): RunResult = parseBaseline(Files.readString(path))

internal fun parseBaseline(text: String): RunResult {
    val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
    val header = lines.firstOrNull()?.split(SEPARATOR).orEmpty()
    require(header.getOrNull(0) == MARKER && header.getOrNull(1) == VERSION) {
        "not a Kestrel baseline, or written by a different version: ${lines.firstOrNull()}"
    }

    val startedAt = lines.first { it.startsWith("run$SEPARATOR") }.split(SEPARATOR)[1]
    val counts = lines.filter { it.startsWith("step$SEPARATOR") }.map { it.split(SEPARATOR) }
    val buckets = lines.filter { it.startsWith("service$SEPARATOR") || it.startsWith("response$SEPARATOR") }
        .map { it.split(SEPARATOR) }
        .groupBy { it[0] to it[1].unescaped() }

    val steps = counts.associate { fields ->
        val name = fields[1].unescaped()
        name to StepStats(
            name = name,
            count = fields[2].toLong(),
            ok = fields[3].toLong(),
            // Reasons are not kept: a baseline exists to answer "did this get
            // slower", and a failure that mattered is in the run's own report.
            failures = emptyMap(),
            serviceTime = buckets["service" to name].orEmpty().asTiming(),
            responseTime = buckets["response" to name].orEmpty().asTiming(),
        )
    }

    return RunResult(
        startedAt = Instant.parse(startedAt),
        steps = steps,
        behind = Histogram().timing(),
        plan = lines.asPlan(),
        machine = lines.asMachine(),
    )
}

private fun List<String>.asMachine(): Machine {
    val (_, cores, jdk, os, arch) = first { it.startsWith("machine$SEPARATOR") }.split(SEPARATOR)
    return Machine(cores = cores.toInt(), jdk = jdk.unescaped(), os = os.unescaped(), arch = arch.unescaped())
}

// Goals are not kept: a threshold is what a team wanted of the numbers rather
// than work sent at the target, and it is not part of what makes two runs
// comparable.
private fun List<String>.asPlan(): Plan = Plan(
    scenario = first { it.startsWith("plan$SEPARATOR") }.split(SEPARATOR)[1].unescaped(),
    steps = filter { it.startsWith("planned$SEPARATOR") }.map { it.split(SEPARATOR)[1].unescaped() },
    profile = filter { it.startsWith("profile$SEPARATOR") }.map { it.split(SEPARATOR) }.asProfile(),
)

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

            else -> throw IllegalArgumentException("not a rate line this version knows: ${fields.joinToString()}")
        }
    }.lastOrNull()

private fun List<List<String>>.asTiming(): Timing {
    val buckets = map { fields -> Bucket(fields[2].toLong().nanoseconds, fields[3].toLong()) }
    val count = buckets.sumOf { it.count }
    return Timing(
        count = count,
        p50 = buckets.at(count, HALF),
        p95 = buckets.at(count, NINETY_FIVE),
        p99 = buckets.at(count, NINETY_NINE),
        max = buckets.lastOrNull()?.upperBound ?: Duration.ZERO,
        distribution = buckets,
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

private const val MARKER = "kestrel-baseline"
private const val VERSION = "2"
private const val SEPARATOR = "\t"
private const val HALF = 0.5
private const val NINETY_FIVE = 0.95
private const val NINETY_NINE = 0.99
