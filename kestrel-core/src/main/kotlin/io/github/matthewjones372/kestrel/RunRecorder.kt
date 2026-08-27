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
     * @param at how long into the run the request left, which decides the
     *   second it is counted in. Passed in rather than read from a clock here:
     *   the timeline is then a function of what a caller recorded, and a test
     *   of it needs no elapsed time.
     */
    fun record(step: String, failure: String?, serviceTime: Duration, schedulingDelay: Duration, at: Duration) {
        require(at >= Duration.ZERO) { "a request cannot have left before the run began, but left at $at" }
        behind.record(schedulingDelay)
        steps.getOrPut(step) { StepRecorder() }.record(failure, serviceTime, serviceTime + schedulingDelay, at)
    }

    /**
     * A record the sink answered for. [latency] is already measured from the
     * departure the profile promised, so it is both times: there is no separate
     * service time for a stage nothing here called.
     *
     * @param at how long into the run the answer was observed. A step that
     *   departs and is answered elsewhere lands on the timeline where it was
     *   answered, because the second it left in is not something this path
     *   measured.
     */
    fun arrived(step: String, latency: Duration, at: Duration) {
        require(at >= Duration.ZERO) { "an answer cannot have arrived before the run began, but arrived at $at" }
        steps.getOrPut(step) { StepRecorder() }.record(failure = null, service = latency, response = latency, at = at)
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
        timeline = everySecond().freeze(),
    )

    /**
     * The run's own seconds, merged from the steps' rather than counted beside
     * them: a p99 of everything cannot be recovered from three p99s, and a
     * second's count kept twice is two numbers that can disagree.
     */
    private fun everySecond(): Seconds {
        val all = Seconds()
        steps.values.forEach { all.merge(it.seconds) }
        return all
    }

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

    // Four histograms rather than two, and no fifth: the whole-step timings are
    // these merged at freeze, so they cannot drift from the sides they sum.
    private val ok = OutcomeRecorder()
    private val failed = OutcomeRecorder()
    private val failures = LinkedHashMap<String, Long>()

    // The accumulator AGENTS.md allows a builder: it is added to as shards are
    // merged and frozen into StepStats, and never escapes mutable.
    private var outstanding: Outstanding = Outstanding.none

    val seconds = Seconds()

    fun record(failure: String?, service: Duration, response: Duration, at: Duration) {
        if (failure == null) {
            ok.record(service, response)
        } else {
            failed.record(service, response)
            countFailure(failure, 1L)
        }
        seconds.record(at, failure, service)
    }

    fun leftOver(more: Outstanding) {
        outstanding = Outstanding(
            unmatched = outstanding.unmatched + more.unmatched,
            inFlight = outstanding.inFlight + more.inFlight,
        )
    }

    fun merge(other: StepRecorder) {
        ok.merge(other.ok)
        failed.merge(other.failed)
        seconds.merge(other.seconds)
        other.failures.forEach { (reason, seen) -> countFailure(reason, seen) }
        leftOver(other.outstanding)
    }

    fun freeze(name: String): StepStats = StepStats(
        name = name,
        ok = ok.freeze(),
        failed = failed.freeze(failures.toMap()),
        serviceTime = ok.serviceTime.and(failed.serviceTime).timing(),
        responseTime = ok.responseTime.and(failed.responseTime).timing(),
        unmatched = outstanding.unmatched,
        inFlight = outstanding.inFlight,
        timeline = seconds.freeze(),
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

/**
 * One coarse histogram per second of the run so far, from the run's start.
 *
 * The list is grown to reach a second rather than keyed by one, which is what
 * makes a quiet second a zero the reader can see instead of a key nobody
 * wrote.
 */
private class Seconds {

    private val counted = ArrayList<SecondRecorder>()

    fun record(at: Duration, failure: String?, service: Duration) =
        reaching(at.inWholeSeconds.toInt()).record(failure, service)

    fun merge(other: Seconds) = other.counted.forEachIndexed { index, theirs -> reaching(index).merge(theirs) }

    fun freeze(): List<Second> = counted.map { it.freeze() }

    // The only allocation on the recording path, and it happens when a second
    // starts rather than when a request does.
    private fun reaching(second: Int): SecondRecorder {
        while (counted.size <= second) counted.add(SecondRecorder())
        return counted[second]
    }
}

private class SecondRecorder {

    private val ok = Histogram.coarse()

    // The accumulator AGENTS.md allows a builder, and null until something
    // fails: a second nothing failed in is most seconds of most runs, and the
    // timeline keeps one of these per second per step. Which reason it was is
    // not kept — that would be an allocation the report then calls the
    // target's latency.
    private var failed: Histogram? = null

    fun record(failure: String?, service: Duration) =
        if (failure == null) ok.record(service) else failing().record(service)

    fun merge(other: SecondRecorder) {
        ok.merge(other.ok)
        other.failed?.let { failing().merge(it) }
    }

    fun freeze(): Second = Second(ok.timing(), failed?.timing() ?: Timing.none)

    private fun failing(): Histogram = failed ?: Histogram.coarse().also { failed = it }
}

private class OutcomeRecorder {

    val serviceTime = Histogram()
    val responseTime = Histogram()

    fun record(service: Duration, response: Duration) {
        serviceTime.record(service)
        responseTime.record(response)
    }

    fun merge(other: OutcomeRecorder) {
        serviceTime.merge(other.serviceTime)
        responseTime.merge(other.responseTime)
    }

    fun freeze(reasons: Map<String, Long> = emptyMap()): Outcome =
        Outcome(serviceTime.timing(), responseTime.timing(), reasons)
}

/** A third histogram holding both, allocated at freeze rather than on the timed path. */
private fun Histogram.and(other: Histogram): Histogram =
    Histogram().also { both -> both.merge(this); both.merge(other) }
