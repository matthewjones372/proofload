package io.github.matthewjones372.kestrel.engine

import io.github.matthewjones372.kestrel.Progress
import io.github.matthewjones372.kestrel.Snapshot
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What the scheduler has sent, counted by the scheduler.
 *
 * Only the thread that fires departures writes here, so the count needs no
 * read-modify-write and the reader needs no lock: two volatile stores on a loop
 * whose delay this tool would otherwise report as the target's response time. A
 * watcher reading a departure or two behind is reading a progress line, not a
 * result — the frozen `RunResult` remains the only thing anybody quotes.
 */
internal class Departed {

    // The mutable accumulator on the path being timed: an atomic would put a
    // read-modify-write on the one loop that must not pause, to order writes
    // that only ever come from one thread.
    @Volatile private var count = 0L

    @Volatile private var lateness = 0L

    /** A user has just been handed to a thread, [nanos] after the offset its profile named. */
    fun left(nanos: Long) {
        count += 1
        lateness = nanos
    }

    fun snapshot(
        inFlight: Long,
        ended: Boolean,
        scheduled: Duration,
        requests: Long = 0L,
        failed: Long = 0L,
    ): Snapshot = Snapshot(
        departed = count,
        inFlight = inFlight,
        behind = lateness.nanoseconds,
        ended = ended,
        scheduled = scheduled,
        requests = requests,
        failed = failed,
    )
}

/** A run that is being watched, and the thread the ticks arrive on. */
internal class ProgressWatch(
    private val executor: ScheduledExecutorService,
    private val progress: Progress,
    private val runStart: Long,
    private val take: (Boolean) -> Snapshot,
) {

    /** The last line, once nothing more will depart. */
    fun stop() {
        executor.shutdownNow()
        // Waited on so the sampler is gone before the last tick: a reporter
        // that throttles is then reading its own state from one thread, and
        // the end of the run cannot land beside a sample of it.
        executor.awaitTermination(SHUTDOWN_SECONDS, TimeUnit.SECONDS)
        progress.tick(elapsed(runStart), take(true))
    }
}

/**
 * Ticks [progress] until the returned watch is stopped.
 *
 * The sampling is faster than any line is due, because the interval a reporter
 * wants is the reporter's and this interface has one method. A tick allocates
 * one `Snapshot` on a thread of its own, which no departure and no step is ever
 * submitted to.
 */
internal fun watchProgress(progress: Progress, runStart: Long, take: (Boolean) -> Snapshot): ProgressWatch {
    val executor = Executors.newSingleThreadScheduledExecutor(::progressThread)
    executor.scheduleAtFixedRate(
        { progress.tick(elapsed(runStart), take(false)) },
        SAMPLE.inWholeNanoseconds,
        SAMPLE.inWholeNanoseconds,
        TimeUnit.NANOSECONDS,
    )
    return ProgressWatch(executor, progress, runStart, take)
}

private fun elapsed(runStart: Long): Duration = (System.nanoTime() - runStart).nanoseconds

private fun progressThread(runnable: Runnable): Thread =
    Thread(runnable, "kestrel-progress").apply { isDaemon = true }

/** Fine enough that a line due on any whole second is at most one sample late. */
private val SAMPLE = 1.seconds

// The ticks do not block, so an interrupted one is already finished.
private const val SHUTDOWN_SECONDS = 5L
