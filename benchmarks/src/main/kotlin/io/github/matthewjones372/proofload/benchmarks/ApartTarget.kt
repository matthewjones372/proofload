package io.github.matthewjones372.proofload.benchmarks

import io.github.matthewjones372.proofload.Bucket
import io.github.matthewjones372.proofload.Timing
import io.github.matthewjones372.proofload.timing
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readLines
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The same target as [loopback], in a JVM of its own.
 *
 * `docs/what-it-costs.md` measures the shipped HTTP step against a target
 * sharing this process's heap, its JIT and its cores, and then says it cannot
 * say whose limit the number is. This moves the target out of the way. What it
 * does not move is the machine — that is `spec-0118-cores`.
 *
 * Nothing here is on the measured path: the generator holds a base URL, and the
 * counts arrive after the run is over.
 */
class ApartTarget internal constructor(val baseUrl: String)

/**
 * What [block] answered, and what the target on the other end of it says it
 * served.
 *
 * Two values rather than one, because the sweep needs both and a caller holding
 * a `var` across the lambda to keep the first one is the accumulator AGENTS.md
 * asks for a reason before writing.
 */
data class Apart<T>(val answered: T, val served: Timing)

/**
 * Starts a target in another JVM, hands it to [block], and stops it however
 * [block] ends.
 *
 * The same shape as [loopback], so a sweep reads the same either way. What
 * differs is when the served timing can be read: it is counted in a process
 * this one cannot see into, so it arrives at the end rather than during.
 */
fun <T> apart(block: (ApartTarget) -> T): Apart<T> {
    val counts = createTempFile("proofload-served", ".txt")
    val target = ProcessBuilder(java(), "-cp", classpath(), TARGET_MAIN, counts.toString())
        // Inherited, so a target that failed to start says so where the sweep
        // is being watched rather than into a pipe nobody reads.
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()
    return try {
        val baseUrl = target.inputStream.bufferedReader().readLine()
            ?: error("the target stopped before it said where it was listening")
        val answered = block(ApartTarget(baseUrl.trim()))
        // Closing its input is the stop signal. Reading a stream to its end is
        // the whole protocol, so there is no signal handler to get right — and
        // no kill, so the target writes its counts on the way out rather than
        // being taken away mid-write.
        target.outputStream.close()
        check(target.waitFor(STOPPING.inWholeSeconds, TimeUnit.SECONDS)) {
            "the target did not stop within $STOPPING"
        }
        Apart(answered, parseServed(counts.readLines()))
    } finally {
        target.destroyForcibly()
        counts.deleteIfExists()
    }
}

/**
 * What a target served, as lines: the precision it counted at, then one bucket
 * a line.
 *
 * The buckets rather than the percentiles, because a percentile computed either
 * side of a file is a second implementation of the arithmetic, and `timing`
 * already reads them off buckets counted somewhere else.
 */
internal fun servedLines(served: Timing): List<String> =
    listOf("${served.precision}") + served.distribution.map { "${it.upperBound.inWholeNanoseconds} ${it.count}" }

/** The other half of [servedLines], and the reason the format is written once. */
internal fun parseServed(lines: List<String>): Timing {
    // A target that wrote nothing is a target that died, and a zero here would
    // be published as a served time. The empty *distribution* below is a real
    // reading — a target that answered nothing — and is left alone.
    check(lines.isNotEmpty()) { "the target wrote no counts" }
    val precision = lines.first().toDoubleOrNull()
    return lines.drop(1)
        .filter { it.isNotBlank() }
        .map { line ->
            val (nanos, count) = line.split(' ')
            Bucket(nanos.toLong().nanoseconds, count.toLong())
        }
        .timing(precision)
}

private fun java(): String = Path.of(System.getProperty("java.home"), "bin", "java").toString()

private fun classpath(): String = System.getProperty("java.class.path")

private const val TARGET_MAIN = "io.github.matthewjones372.proofload.benchmarks.ApartTargetMainKt"

/** Long enough for a target to write its counts, short enough that a stuck one fails the sweep. */
private val STOPPING: Duration = 30.seconds
