package io.github.matthewjones372.proofload.benchmarks

import io.github.matthewjones372.proofload.RunRecorder
import java.lang.management.ManagementFactory
import java.time.Instant
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// Spec 0049's open question 3 names the run that would break this: an hour of
// ten steps is 36,000 seconds of tables, and the answer is supposed to be a
// number somebody measured rather than a reassurance.
private const val SOAK_SECONDS = 3_600
private const val STEPS = 10
private const val EACH_SECOND = 20

// System.gc() is a request. Several in a row, with the reading taken after the
// last, is what the existing figures in docs/what-it-costs.md were taken with.
private const val SETTLE_PASSES = 5

private const val BYTES_A_MIB = 1024.0 * 1024.0

/**
 * What `result.timeline` costs on the run that costs the most: the recorder
 * while it is filling, and the frozen seconds that outlive it.
 *
 * Kept apart from the tests because it holds hundreds of megabytes on purpose
 * and reads the heap, neither of which survives sharing a JVM with anything.
 */
fun main() {
    val idle = settledHeap()

    val recorder = RunRecorder(Instant.now())
    repeat(SOAK_SECONDS) { second ->
        repeat(STEPS) { step ->
            repeat(EACH_SECOND) { request ->
                recorder.record(
                    step = "step $step",
                    failure = null,
                    serviceTime = (10 + request).milliseconds,
                    schedulingDelay = 5.milliseconds,
                    at = second.seconds + (request * 50).milliseconds,
                )
            }
        }
    }
    val recording = settledHeap() - idle

    val result = recorder.freeze()
    val withFrozen = settledHeap() - idle
    val seconds = result.steps.values.sumOf { it.timeline.size }

    println("a $SOAK_SECONDS-second run of $STEPS steps at $EACH_SECOND requests a second")
    println("  seconds of tables:   $seconds")
    println("  recording:           ${recording.mib()} MiB")
    println("  recorder and frozen: ${withFrozen.mib()} MiB")
    println("  per second of step:  ${recording / seconds} bytes recording")
    // Read after the last measurement so nothing above can be collected early.
    println("  (counted ${result.count} requests)")
}

private fun settledHeap(): Long {
    repeat(SETTLE_PASSES) { System.gc() }
    return ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
}

private fun Long.mib(): String = String.format(Locale.ROOT, "%.1f", this / BYTES_A_MIB)
