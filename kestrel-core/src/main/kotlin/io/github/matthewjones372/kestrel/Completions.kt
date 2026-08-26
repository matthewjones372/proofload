package io.github.matthewjones372.kestrel

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * Which id the sink will answer with, read from the session the publish left
 * behind. A long and nothing richer: the id is a field the caller already has,
 * and anything larger would make this tool the owner of a wire format.
 */
fun interface Correlation {
    fun of(session: Session): Long
}

/**
 * A sink an engine drains for the ids it has observed. Core declares it without
 * a broker type, the way [Action] is declared without a protocol one, so the
 * module carrying the client stays a leaf.
 */
fun interface Completions {

    /**
     * The ids observed since the last call, back as soon as there are any and
     * by [within] at the latest. An arrival is stamped when this returns, so an
     * implementation that holds a batch back reports its own delay as the
     * pipeline's.
     */
    fun poll(within: Duration): List<Long>
}

/**
 * Completions handed over in the same process: what stands in for a broker
 * until a module carries one, and enough to exercise the shape end to end.
 */
class InMemoryCompletions : Completions {

    private val observed = LinkedBlockingQueue<Long>()

    /** The sink saw the record [id] answers for. */
    fun observe(id: Long) {
        observed.put(id)
    }

    override fun poll(within: Duration): List<Long> {
        // The wait belongs to the sink and is bounded by its caller. It happens
        // on the thread draining completions, never on the path a step is timed
        // on, so it is a wait for an answer rather than a stall in a generator.
        val first = observed.poll(within.inWholeNanoseconds, TimeUnit.NANOSECONDS) ?: return emptyList()
        return buildList {
            add(first)
            observed.drainTo(this)
        }
    }
}

/** Departures a sink never answered for, split by whether it was given the window to answer in. */
data class Outstanding(val unmatched: Long, val inFlight: Long) {

    companion object {
        val none: Outstanding = Outstanding(unmatched = 0L, inFlight = 0L)
    }
}

/**
 * The departures a sink has still to answer for.
 *
 * Shared between the users that depart and the thread draining the sink, so it
 * is the one structure in the recording path that is thread-safe. A publish
 * registers here after it has been timed, never during.
 */
class Pending {

    private val departures = ConcurrentHashMap<Long, Departure>()
    private val unclaimed = ConcurrentHashMap<Long, Duration>()

    /**
     * @param intended the departure the profile promised, which every latency
     *   here is measured from.
     * @param at when the record actually left, which is all of the window in
     *   [close] it can fairly be judged against.
     */
    fun departed(id: Long, intended: Duration, at: Duration) {
        departures[id] = Departure(intended, at)
    }

    /**
     * How long [id] took to reach the sink, measured from the departure its
     * profile promised — or null, when the observation is held for [matched] to
     * pair up later.
     */
    fun observed(id: Long, at: Duration): Duration? {
        val departure = departures.remove(id) ?: run {
            unclaimed[id] = at
            return null
        }
        return at - departure.intended
    }

    /**
     * Latencies for observations whose departures had not registered when they
     * were seen. A sink can answer before the publish call returns, and that is
     * a fast pipeline rather than a record nobody sent.
     */
    fun matched(): List<Duration> = unclaimed.keys.toList().mapNotNull { id ->
        departures[id]?.let { departure ->
            departures.remove(id)
            unclaimed.remove(id)?.minus(departure.intended)
        }
    }

    /**
     * What is left at [at], once nothing more will be drained. A record the
     * sink had the whole [window] to answer for and did not is gone; one that
     * left too late to have been given that window is still moving, and the two
     * are never added together.
     */
    fun close(at: Duration, window: Duration): Outstanding {
        val left = departures.values.toList()
        val gone = left.count { departure -> at - departure.at >= window }.toLong()
        return Outstanding(unmatched = gone, inFlight = left.size - gone)
    }
}

private data class Departure(val intended: Duration, val at: Duration)
