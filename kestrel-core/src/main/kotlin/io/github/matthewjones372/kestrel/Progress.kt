package io.github.matthewjones372.kestrel

import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How far a run has got, as the scheduler sees it: [departed] users sent,
 * [inFlight] of them still running, and the last departure [behind] the offset
 * its profile named. [ended] marks the run's last tick.
 *
 * Nothing here comes from what was recorded. A live histogram read for a
 * percentile, or shards merged to count requests, would put the watcher's work
 * on the path being timed, and a tool that moves what it measures reports its
 * own weight as the target's latency. These are numbers the scheduler already
 * keeps, so a run costs the same watched as unwatched.
 */
data class Snapshot(
    val departed: Long,
    val inFlight: Long,
    val behind: Duration,
    val ended: Boolean,
)

/**
 * Where a run says what it is doing while it is still doing it, so a ten-minute
 * soak is not ten minutes of silence somebody kills.
 */
fun interface Progress {

    fun tick(elapsed: Duration, snapshot: Snapshot)

    companion object {

        /** For a caller whose output is somebody else's report — a test framework, a CI step that parses stdout. */
        val silent: Progress = Progress { _, _ -> }

        /**
         * A line on stdout no more often than [every], and one when the run ends.
         *
         * The engine samples faster than a line is due, so [every] is this
         * reporter's own throttle rather than the rate it is asked at; the
         * run's last tick prints whenever it lands, so a run shorter than one
         * interval still says what it did.
         */
        fun lines(every: Duration = 5.seconds): Progress = Lines(every)
    }
}

/**
 * The ticks a run's sampler sends and the one its own thread sends at the end
 * never overlap — the sampler is stopped first — so this holds the next due
 * time rather than guarding it.
 */
private class Lines(private val every: Duration) : Progress {

    private val due = AtomicLong(every.inWholeNanoseconds)

    override fun tick(elapsed: Duration, snapshot: Snapshot) {
        if (!snapshot.ended && elapsed.inWholeNanoseconds < due.get()) return
        due.set(elapsed.inWholeNanoseconds + every.inWholeNanoseconds)
        println(snapshot.line(elapsed))
    }
}

// The same `kestrel:` prefix the report writer uses, so a run reads as one
// program saying several things rather than as two programs talking over
// each other.
private fun Snapshot.line(elapsed: Duration): String =
    "kestrel: ${elapsed.clock()}  departed ${departed.grouped()}  " +
        "in flight ${inFlight.grouped()}  behind $behind"

// Built rather than formatted, so the line reads the same under every locale. A
// soak past an hour counts on in minutes instead of growing a third field.
private fun Duration.clock(): String = "${inWholeMinutes.padded()}:${(inWholeSeconds % SECONDS_PER_MINUTE).padded()}"

private fun Long.padded(): String = toString().padStart(2, '0')

private fun Long.grouped(): String = toString().reversed().chunked(THOUSAND).joinToString(",").reversed()

private const val SECONDS_PER_MINUTE = 60

private const val THOUSAND = 3
