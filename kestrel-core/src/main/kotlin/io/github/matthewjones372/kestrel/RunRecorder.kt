package io.github.matthewjones372.kestrel

import java.time.Instant
import kotlin.time.Duration

/**
 * Where an engine writes what it saw. Deliberately not thread-safe: each
 * recording thread keeps its own and they are merged at the end, because a
 * shared structure puts a lock on the path being timed and the tool starts
 * measuring itself.
 */
class RunRecorder(private val startedAt: Instant) {

    private val steps = LinkedHashMap<String, StepRecorder>()
    private val behind = Histogram()

    /**
     * @param schedulingDelay how late the request left against the departure the
     *   profile promised. Added to service time to give the response time a
     *   user would have seen, and kept on its own so the backlog has a name.
     */
    fun record(step: String, failure: String?, serviceTime: Duration, schedulingDelay: Duration) {
        behind.record(schedulingDelay)
        steps.getOrPut(step) { StepRecorder() }.record(failure, serviceTime, serviceTime + schedulingDelay)
    }

    /**
     * A record the sink answered for. [latency] is already measured from the
     * departure the profile promised, so it is both times: there is no separate
     * service time for a stage nothing here called.
     */
    fun arrived(step: String, latency: Duration) {
        steps.getOrPut(step) { StepRecorder() }.record(failure = null, service = latency, response = latency)
    }

    /** What the sink never answered for, once the drain window has closed. */
    fun outstanding(step: String, outstanding: Outstanding) {
        steps.getOrPut(step) { StepRecorder() }.leftOver(outstanding)
    }

    fun merge(other: RunRecorder) {
        behind.merge(other.behind)
        other.steps.forEach { (name, theirs) -> steps.getOrPut(name) { StepRecorder() }.merge(theirs) }
    }

    fun freeze(): RunResult = RunResult(
        startedAt = startedAt,
        steps = steps.mapValues { (name, recorder) -> recorder.freeze(name) },
        behind = behind.timing(),
    )

    companion object {
        /**
         * A reason with an order id in it makes a key per request. Past this
         * many, the rest are counted together rather than turning a report
         * into a list of one-offs.
         */
        const val MAX_REASONS_PER_STEP: Int = 20

        const val OTHER_REASONS: String = "other"
    }
}

private class StepRecorder {

    private val serviceTime = Histogram()
    private val responseTime = Histogram()
    private val failures = LinkedHashMap<String, Long>()

    // The accumulator AGENTS.md allows a builder: it is added to as shards are
    // merged and frozen into StepStats, and never escapes mutable.
    private var outstanding: Outstanding = Outstanding.none

    // Both derived rather than counted: a count kept beside the histogram is a
    // second number to keep in step, and the two disagreeing is a bug nobody
    // would see until a report looked odd.
    private val count: Long get() = serviceTime.count
    private val ok: Long get() = count - failures.values.sum()

    fun record(failure: String?, service: Duration, response: Duration) {
        serviceTime.record(service)
        responseTime.record(response)
        if (failure != null) countFailure(failure, 1L)
    }

    fun leftOver(more: Outstanding) {
        outstanding = Outstanding(
            unmatched = outstanding.unmatched + more.unmatched,
            inFlight = outstanding.inFlight + more.inFlight,
        )
    }

    fun merge(other: StepRecorder) {
        serviceTime.merge(other.serviceTime)
        responseTime.merge(other.responseTime)
        other.failures.forEach { (reason, seen) -> countFailure(reason, seen) }
        leftOver(other.outstanding)
    }

    fun freeze(name: String): StepStats = StepStats(
        name = name,
        count = count,
        ok = ok,
        failures = failures.toMap(),
        serviceTime = serviceTime.timing(),
        responseTime = responseTime.timing(),
        unmatched = outstanding.unmatched,
        inFlight = outstanding.inFlight,
    )

    private fun countFailure(reason: String, seen: Long) {
        val key = when {
            reason in failures -> reason
            failures.size < RunRecorder.MAX_REASONS_PER_STEP -> reason
            else -> RunRecorder.OTHER_REASONS
        }
        failures[key] = (failures[key] ?: 0L) + seen
    }
}
