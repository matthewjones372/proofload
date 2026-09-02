package io.github.matthewjones372.kestrel

import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Where an engine writes what it saw. Deliberately not thread-safe: each
 * recording thread keeps its own and they are merged at the end, because a
 * shared structure puts a lock on the path being timed and the tool starts
 * measuring itself.
 */
class RunRecorder(private val startedAt: Instant, private val origin: Long) {

    /**
     * A recorder for a run beginning now. The origin is read here rather than
     * asked of a caller, because the object that holds a run's measurements is
     * the one place a run's zero point can live without being agreed.
     */
    constructor(startedAt: Instant) : this(startedAt, System.nanoTime())

    private val steps = LinkedHashMap<String, StepRecorder>()
    private val behind = Histogram()

    // Beside the run's own seconds rather than each step's: a departure is
    // late once, for the user, not once per step it goes on to make. One
    // coarse table a second at run level, which is what makes it affordable.
    private val lateness = Lateness()

    /**
     * How long this run has been going, on the monotonic clock this recorder
     * was made with.
     *
     * For a caller that records without having been told the offset — a
     * transport called from inside a step, which knows when its own request
     * left and nothing about when the run did. A caller that already computed
     * the offset passes it instead, and the engine does.
     */
    fun sinceStart(): Duration = (System.nanoTime() - origin).nanoseconds

    /**
     * Another recorder for this same run: same start, same origin, its own
     * histograms.
     *
     * Shards are merged at the end, so they have to agree about when second
     * zero was. Spawned from the recorder rather than each reading the clock,
     * the agreement is by construction rather than by how close together they
     * happened to be built.
     */
    fun shard(): RunRecorder = RunRecorder(startedAt, origin)

    /**
     * @param schedulingDelay how late the request left against the departure the
     *   profile promised. Added to service time to give the response time a
     *   user would have seen, and kept on its own so the backlog has a name.
     * @param at how long into the run the request left, which decides the
     *   second it is counted in. Passed in rather than read from a clock here:
     *   the timeline is then a function of what a caller recorded, and a test
     *   of it needs no elapsed time.
     * @param reached whether this is the first request this user has made under
     *   [step]. Told rather than worked out here: only the walk knows which user
     *   it is on, and a recorder that guessed would count a loop's iterations as
     *   users. It defaults to false, so a caller that does not track users
     *   reports no reaches instead of a number nobody counted.
     */
    fun record(
        step: String,
        failure: Reason?,
        serviceTime: Duration,
        schedulingDelay: Duration,
        at: Duration,
        reached: Boolean = false,
        attempts: Int = 1,
    ) {
        require(at >= Duration.ZERO) { "a request cannot have left before the run began, but left at $at" }
        behind.record(schedulingDelay)
        lateness.record(at, schedulingDelay)
        steps.getOrPut(step) { StepRecorder() }
            .record(failure, serviceTime, serviceTime + schedulingDelay, at, reached, attempts)
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
        lateness.merge(other.lateness)
        other.steps.forEach { (name, theirs) -> steps.getOrPut(name) { StepRecorder() }.merge(theirs) }
    }

    fun freeze(): RunResult = RunResult(
        startedAt = startedAt,
        steps = steps.mapValues { (name, recorder) -> recorder.freeze(name) },
        behind = behind.timing(),
        timeline = everySecond().freeze(),
        latePerSecond = lateness.freeze(),
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
    }
}

private class StepRecorder {

    // Four histograms rather than two, and no fifth: the whole-step timings are
    // these merged at freeze, so they cannot drift from the sides they sum.
    private val ok = OutcomeRecorder()
    private val failed = OutcomeRecorder()
    private val failures = LinkedHashMap<Reason, Long>()

    // The accumulator AGENTS.md allows a builder: it is added to as shards are
    // merged and frozen into StepStats, and never escapes mutable.
    private var outstanding: Outstanding = Outstanding.none

    // The same, one number: a user is counted here the first time it records
    // under this step, which is the only moment anything knows it is the first.
    private var reached = 0L

    // And the round trips behind those requests: one each unless a step
    // followed a redirect or retried.
    private var attempts = 0L

    val seconds = Seconds()

    fun record(
        failure: Reason?,
        service: Duration,
        response: Duration,
        at: Duration,
        reached: Boolean = false,
        attempts: Int = 1,
    ) {
        if (reached) this.reached++
        this.attempts += attempts
        if (failure == null) {
            ok.record(service, response)
        } else {
            failed.record(service, response)
            countFailure(failure, 1L)
        }
        seconds.record(at, failure, service, response)
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
        reached += other.reached
        attempts += other.attempts
        other.failures.forEach { (reason, seen) -> countFailure(reason, seen) }
        leftOver(other.outstanding)
    }

    fun freeze(name: String): StepStats = StepStats(
        name = name,
        ok = ok.freeze(),
        failed = failed.freeze(failures.toMap()),
        serviceTime = ok.serviceTime.and(failed.serviceTime).timing(),
        responseTime = ok.responseTime.and(failed.responseTime).timing(),
        reached = reached,
        attempts = attempts,
        unmatched = outstanding.unmatched,
        inFlight = outstanding.inFlight,
        timeline = seconds.freeze(),
    )

    private fun countFailure(reason: Reason, seen: Long) {
        val key = when {
            reason in failures -> reason
            failures.size < RunRecorder.MAX_REASONS_PER_STEP -> reason
            else -> Other
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

    fun record(at: Duration, failure: Reason?, service: Duration, response: Duration) =
        reaching(at.inWholeSeconds.toInt()).record(failure, service, response)

    fun merge(other: Seconds) = other.counted.forEachIndexed { index, theirs -> reaching(index).merge(theirs) }

    fun freeze(): List<Second> = counted.map { it.freeze() }

    // The only allocation on the recording path, and it happens when a second
    // starts rather than when a request does.
    private fun reaching(second: Int): SecondRecorder {
        while (counted.size <= second) counted.add(SecondRecorder())
        return counted[second]
    }
}

/**
 * How late the departures of each second were, from the run's start.
 *
 * The whole-run [RunRecorder.behind] cannot say *when* a schedule was lost,
 * only that it was, so a reader is told to lower the rate with no idea which
 * rate held. Kept coarse and only at run level: one table a second for the run
 * is a few kilobytes a minute, where one per step per second is what 0025
 * refused to pay for a second time.
 */
private class Lateness {

    private val counted = ArrayList<Histogram>()

    fun record(at: Duration, schedulingDelay: Duration) =
        reaching(at.inWholeSeconds.toInt()).record(schedulingDelay)

    fun merge(other: Lateness) = other.counted.forEachIndexed { index, theirs -> reaching(index).merge(theirs) }

    fun freeze(): List<Timing> = counted.map { it.timing() }

    private fun reaching(second: Int): Histogram {
        while (counted.size <= second) counted.add(Histogram.coarse())
        return counted[second]
    }
}

private class SecondRecorder {

    private val ok = Clocks()

    // The accumulator AGENTS.md allows a builder, and null until something
    // fails: a second nothing failed in is most seconds of most runs, and the
    // timeline keeps one of these per second per step. Which reason it was is
    // not kept — that would be an allocation the report then calls the
    // target's latency.
    private var failed: Clocks? = null

    fun record(failure: Reason?, service: Duration, response: Duration) =
        if (failure == null) ok.record(service, response) else failing().record(service, response)

    fun merge(other: SecondRecorder) {
        ok.merge(other.ok)
        other.failed?.let { failing().merge(it) }
    }

    fun freeze(): Second = Second(
        okServiceTime = ok.serviceTime.timing(),
        failedServiceTime = failed?.serviceTime?.timing() ?: Timing.none,
        okResponseTime = ok.responseTime.timing(),
        failedResponseTime = failed?.responseTime?.timing() ?: Timing.none,
    )

    private fun failing(): Clocks = failed ?: Clocks().also { failed = it }
}

/**
 * One side of one second on both clocks, coarse.
 *
 * The pair is allocated together because it is filled together: a request that
 * has a service time in this second has a response time in it too, so a lazily
 * allocated second table would be a branch on the timed path buying nothing.
 */
private class Clocks {

    val serviceTime = Histogram.coarse()

    val responseTime = Histogram.coarse()

    fun record(service: Duration, response: Duration) {
        serviceTime.record(service)
        responseTime.record(response)
    }

    fun merge(other: Clocks) {
        serviceTime.merge(other.serviceTime)
        responseTime.merge(other.responseTime)
    }
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

    fun freeze(reasons: Map<Reason, Long> = emptyMap()): Outcome =
        Outcome(serviceTime.timing(), responseTime.timing(), reasons)
}

/** A third histogram holding both, allocated at freeze rather than on the timed path. */
private fun Histogram.and(other: Histogram): Histogram =
    Histogram().also { both -> both.merge(this); both.merge(other) }
