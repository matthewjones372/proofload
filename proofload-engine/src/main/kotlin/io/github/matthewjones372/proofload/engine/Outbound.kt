package io.github.matthewjones372.proofload.engine

import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The users still out, counted by the second they were meant to depart in.
 *
 * What a run's recorder needs to know to close a second: nothing departing
 * later can record before it left, so once the oldest second here has moved
 * on, every second before it is finished. Keyed by second rather than by user,
 * so it holds a few entries however many users are out.
 */
internal class Outbound {

    private val bySecond = ConcurrentHashMap<Long, Long>()

    fun left(departure: Duration) {
        bySecond.merge(departure.inWholeSeconds, 1L, Long::plus)
    }

    // Removed at zero inside the same atomic step, so a second nobody is out
    // in is never counted as the oldest.
    fun back(departure: Duration) {
        bySecond.compute(departure.inWholeSeconds) { _, out -> if (out == null || out <= 1L) null else out - 1L }
    }

    /** The second the oldest user still out was meant to depart in, or null when none is out. */
    fun oldest(): Duration? = bySecond.keys.minOrNull()?.seconds
}
