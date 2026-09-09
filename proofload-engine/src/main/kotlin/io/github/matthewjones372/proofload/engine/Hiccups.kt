package io.github.matthewjones372.proofload.engine

import io.github.matthewjones372.proofload.Histogram
import io.github.matthewjones372.proofload.Timing
import io.github.matthewjones372.proofload.timing
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

/**
 * How much later than it was due each tick of a fixed schedule actually
 * arrived: the machine and the JVM under the tool, as a distribution.
 *
 * jHiccup asks this by sleeping and measuring what it got back, which this
 * cannot do — a parked thread is a stall the tool would then measure as the
 * target's latency, and `AGENTS.md` forbids it in library code for that reason.
 * A tick due at its own multiple of the interval answers the same question, and
 * a scheduler that ran late reports every tick it held up rather than one late
 * sample and a gap.
 */
internal class Hiccups(private val startedAt: Long, private val interval: Duration) {

    // Written only from the thread the ticks run on and read once that thread's
    // executor has terminated, which is the edge that publishes it.
    private val histogram = Histogram()
    private val ticks = AtomicLong()

    /** Counts the next tick, which was due at its own multiple of the interval and arrived at [firedAt]. */
    fun observe(firedAt: Long) {
        val due = startedAt + ticks.incrementAndGet() * interval.inWholeNanoseconds
        // Floored: a scheduler cannot fire early, and a tick that reads as
        // early is two nanoTime samples disagreeing rather than a stall.
        histogram.record((firedAt - due).coerceAtLeast(0L).nanoseconds)
    }

    fun frozen(): Timing = histogram.timing()
}

/** A hiccup recorder that is running, and the executor it is running on. */
internal class HiccupWatch(private val executor: ScheduledExecutorService, private val hiccups: Hiccups) {

    fun stop(): Timing {
        executor.shutdownNow()
        // Not for the wait: this is what makes what the ticking thread wrote
        // visible to the thread that is about to read it.
        executor.awaitTermination(SHUTDOWN_SECONDS, TimeUnit.SECONDS)
        return hiccups.frozen()
    }
}

/**
 * Starts watching this JVM until the returned watch is stopped.
 *
 * Nothing on the timed path touches any of this: the ticks run on an executor
 * of their own that no departure and no step is ever submitted to, they write a
 * histogram nothing else holds a reference to, and it is read only after that
 * executor has terminated. The one thing shared with the run is the machine,
 * which is the thing being measured.
 */
internal fun watchForHiccups(
    interval: Duration = TICK,
    executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(::hiccupThread),
): HiccupWatch {
    val hiccups = Hiccups(System.nanoTime(), interval)
    executor.scheduleAtFixedRate(
        { hiccups.observe(System.nanoTime()) },
        interval.inWholeNanoseconds,
        interval.inWholeNanoseconds,
        TimeUnit.NANOSECONDS,
    )
    return HiccupWatch(executor, hiccups)
}

private fun hiccupThread(runnable: Runnable): Thread =
    Thread(runnable, "proofload-hiccups").apply { isDaemon = true }

/** What jHiccup asks for, and small enough that a stall worth naming spans several. */
private val TICK = 1.milliseconds

// The ticks do not block, so an interrupted one is already finished.
private const val SHUTDOWN_SECONDS = 5L
