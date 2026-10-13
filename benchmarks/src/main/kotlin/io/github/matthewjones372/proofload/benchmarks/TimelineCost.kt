package io.github.matthewjones372.proofload.benchmarks

import io.github.matthewjones372.proofload.RunRecorder
import io.github.matthewjones372.proofload.RunResult
import java.lang.management.ManagementFactory
import java.time.Instant
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val HOUR = 3_600
private const val DAY = 86_400

// System.gc() is a request. Several in a row, with the reading taken after the
// last, is what the existing figures in docs/what-it-costs.md were taken with.
private const val SETTLE_PASSES = 5

// Past the 127 a boxed Long is cached for, so a reading costs what a busy run's does.
private const val USERS = 1_000

private const val BYTES_A_MIB = 1024.0 * 1024.0

/** A run's length and shape: [steps] steps, each sent [eachSecond] times a second. */
private data class Soak(val name: String, val seconds: Int, val steps: Int, val eachSecond: Int)

private val SOAKS = listOf(
    Soak("hour of ten steps", HOUR, steps = 10, eachSecond = 20),
    Soak("day of ten steps", DAY, steps = 10, eachSecond = 20),
    // The petshop demo's traffic, which a day of is what 0143 is for.
    Soak("day of the demo", DAY, steps = 3, eachSecond = 5),
)

/** What one soak held, in bytes, read off the heap. */
private data class Held(
    val slots: Int,
    val recording: Long,
    val withFrozen: Long,
    val frozen: Long,
    val inFlight: Long,
)

/**
 * What `result.timeline` costs on the runs that cost the most: the recorder
 * while it is filling, the frozen seconds that outlive it, and the engine's
 * once-a-second count of users in flight beside them.
 *
 * Kept apart from the tests because it holds hundreds of megabytes on purpose
 * and reads the heap, neither of which survives sharing a JVM with anything.
 */
fun main() {
    val held = SOAKS.map(::measure)
    println("| | ${SOAKS.joinToString(" | ") { it.name }} |")
    println("|---|${SOAKS.joinToString("") { "---|" }}")
    println("| length, steps, requests a second each | ${SOAKS.joinToString(" | ") { it.shape }} |")
    row("slots", held) { slots.grouped() }
    row("recording", held) { recording.mib() }
    row("recorder and frozen", held) { withFrozen.mib() }
    row("frozen alone", held) { frozen.mib() }
    row("per slot, recording", held) { "${(recording / slots).grouped()} bytes" }
    row("users in flight, on their own", held) { inFlight.mib() }
    println()
    println(machine())
}

private val Soak.shape: String get() = "${seconds.grouped()} s, $steps, $eachSecond"

private fun row(name: String, held: List<Held>, cell: Held.() -> String) =
    println("| $name | ${held.joinToString(" | ") { it.cell() }} |")

private fun measure(soak: Soak): Held {
    val idle = settledHeap()
    val recorder = RunRecorder(Instant.now()).also { soak.run(it, closing = true) }
    val recording = settledHeap() - idle
    val result = recorder.freeze()
    val withFrozen = settledHeap() - idle
    val slots = result.steps.values.sumOf { it.timeline.size }
    return Held(slots, recording, withFrozen, frozenAlone(soak), inFlightAlone(soak))
}

private fun Soak.run(recorder: RunRecorder, closing: Boolean) = repeat(seconds) { second ->
    // What the engine does once a second. Every request here completes in the
    // second it left in, so all the seconds before this one are finished.
    if (closing) recorder.close(before = second.seconds)
    recorder.inFlight(at = second.seconds, users = second % USERS.toLong())
    repeat(steps) { step ->
        repeat(eachSecond) { request ->
            recorder.record(
                step = "step $step",
                failure = null,
                serviceTime = (10 + request).milliseconds,
                schedulingDelay = 5.milliseconds,
                at = second.seconds + (request * 1_000 / eachSecond).milliseconds,
            )
        }
    }
}

/**
 * The same soak frozen with no recorder left holding it, through the path that
 * never closes: the figure a recorder that closes its seconds should come in near.
 */
private fun frozenAlone(soak: Soak): Long {
    val idle = settledHeap()
    val result = frozen(soak)
    val held = settledHeap() - idle
    // Read after the measurement, so the result cannot be collected before it.
    check(result.count == soak.seconds.toLong() * soak.steps * soak.eachSecond)
    return held
}

// A function of its own so the recorder dies with its frame: a local in the
// caller would keep its tables reachable through the measurement.
private fun frozen(soak: Soak): RunResult {
    val recorder = RunRecorder(Instant.now())
    soak.run(recorder, closing = false)
    return recorder.freeze()
}

/** The engine's once-a-second count of users in flight on its own, as its recorder keeps it. */
private fun inFlightAlone(soak: Soak): Long {
    val idle = settledHeap()
    val recorder = RunRecorder(Instant.now())
    repeat(soak.seconds) { recorder.inFlight(at = it.seconds, users = it % USERS.toLong()) }
    val held = settledHeap() - idle
    // Read after the measurement, so the recorder cannot be collected before it.
    check(recorder.freeze().usersInFlight.isNotEmpty())
    return held
}

private fun settledHeap(): Long {
    repeat(SETTLE_PASSES) { System.gc() }
    return ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
}

private fun Long.mib(): String = String.format(Locale.ROOT, "%.2f MiB", this / BYTES_A_MIB)
