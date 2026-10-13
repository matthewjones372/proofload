package io.github.matthewjones372.proofload

import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Where an engine writes what it saw. Deliberately not thread-safe: each
 * recording thread keeps its own and they are merged at the end, because a
 * shared structure puts a lock on the path being timed and the tool starts
 * measuring itself.
 */
class RunRecorder @JvmOverloads constructor(
    private val startedAt: Instant,
    private val origin: Long,
    /**
     * Whether this run promised its departures a time to leave at.
     *
     * False for a closed population, where the target decides when a user goes
     * round again. Lateness is then not recorded at all rather than recorded
     * as zero: a generator is not late for a departure nobody promised, and a
     * `behind` full of zeros would read as perfect punctuality rather than as
     * a question that does not apply.
     */
    private val keepingSchedule: Boolean = true,
    /** How many slots the timeline holds before neighbours are merged and every slot widens. */
    private val slots: Int = SLOTS,
) {

    /**
     * A recorder for a run beginning now. The origin is read here rather than
     * asked of a caller, because the object that holds a run's measurements is
     * the one place a run's zero point can live without being agreed.
     */
    @JvmOverloads
    constructor(startedAt: Instant, keepingSchedule: Boolean = true, slots: Int = SLOTS) :
        this(startedAt, System.nanoTime(), keepingSchedule, slots)

    init {
        require(slots > 0) { "a timeline holds at least one slot, but slots was $slots" }
    }

    private val steps = LinkedHashMap<String, StepRecorder>()
    private val behind = Histogram()

    // One width for every table this recorder keeps, so a step's slots, the
    // run's and the lateness beside them always line up.
    private val width = Width(slots)

    // Beside the run's own seconds rather than each step's: a departure is
    // late once, for the user, not once per step it goes on to make. One
    // coarse table a second at run level, which is what makes it affordable.
    private val lateness = lateness(width)

    private val sampled = Readings(width)

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
    fun shard(): RunRecorder = RunRecorder(startedAt, origin, keepingSchedule, slots)

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
     * @param visit whether this is the first record of this run of the body.
     *   Told for the same reason [reached] is: a body that reported a hundred
     *   samples ran once, and only the walk that called it knows that. Defaults
     *   to false, so a caller that does not track visits reports none rather
     *   than a number nobody counted.
     */
    fun record(
        step: String,
        failure: Reason?,
        serviceTime: Duration,
        schedulingDelay: Duration,
        at: Duration,
        reached: Boolean = false,
        visit: Boolean = false,
        attempts: Int = 1,
        trace: String? = null,
    ) {
        require(at >= Duration.ZERO) { "a request cannot have left before the run began, but left at $at" }
        if (keepingSchedule) {
            behind.record(schedulingDelay)
            lateness.record(at, schedulingDelay)
        }
        stepCalled(step)
            .record(failure, serviceTime, serviceTime + schedulingDelay, at, reached, visit, attempts, trace)
    }

    /**
     * What a step reported about itself rather than about its answer: how long
     * it [queued] for a resource this end of the wire owns, and how much the
     * target [produced].
     *
     * Apart from [record] because it is beside the measurement rather than part
     * of one, and because only a step with something to say calls it — putting
     * two more parameters on the recording path would cost every step that has
     * neither.
     */
    fun aside(step: String, queued: Duration, produced: Long) {
        stepCalled(step).aside(queued, produced)
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
        stepCalled(step).record(failure = null, service = latency, response = latency, at = at)
    }

    /** What the sink never answered for, once the drain window has closed. */
    fun outstanding(step: String, outstanding: Outstanding) {
        stepCalled(step).leftOver(outstanding)
    }

    /**
     * How many [users] a sampler counted running [at] this point in the run,
     * kept one reading a slot so a day holds no more of them than an hour.
     */
    fun inFlight(at: Duration, users: Long) = sampled.take(at.inWholeSeconds.toInt(), users)

    fun merge(other: RunRecorder) {
        behind.merge(other.behind)
        sampled.merge(other.sampled)
        lateness.merge(other.lateness)
        other.steps.forEach { (name, theirs) -> stepCalled(name).merge(theirs) }
    }

    /**
     * Folds every second before [before] into what [freeze] would make of it,
     * and lets its tables go.
     *
     * The caller says when, because only the engine knows the oldest departure
     * still in flight. Told too early, nothing is lost: a record for a closed
     * second is counted beside it and folded in at the next close.
     */
    fun close(before: Duration) {
        val second = before.inWholeSeconds.toInt()
        lateness.close(second)
        steps.values.forEach { it.seconds.close(second) }
    }

    /** How many per-second tables are live, which is what [close] exists to keep down. */
    internal val tablesHeld: Int get() = lateness.held + steps.values.sumOf { it.seconds.held }

    // A step first seen late starts closed where the others are, rather than
    // allocating a table for every quiet second before it.
    private fun stepCalled(name: String): StepRecorder =
        steps.getOrPut(name) { StepRecorder(width).also { it.seconds.close(lateness.before) } }

    fun freeze(): RunResult = RunResult(
        startedAt = startedAt,
        steps = steps.mapValues { (name, recorder) -> recorder.freeze(name) },
        behind = behind.timing(),
        timeline = everySecond().frozen(),
        latePerSecond = lateness.freeze(),
        usersInFlight = sampled.freeze(),
    )

    /**
     * The run's own seconds, merged from the steps' rather than counted beside
     * them: a p99 of everything cannot be recovered from three p99s, and a
     * second's count kept twice is two numbers that can disagree.
     */
    private fun everySecond(): Seconds {
        val all = seconds(width)
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

        /** An hour at one second a slot, and a day at thirty-two. */
        const val SLOTS: Int = 3_600
    }
}

private class StepRecorder(width: Width) {

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

    // The same again, one per run of the body: told by the walk on the first
    // record of each, so a body that reported a hundred samples counts one.
    private var visits = 0L

    // And the round trips behind those requests: one each unless a step
    // followed a redirect or retried.
    private var attempts = 0L

    // Built on the first wait rather than with the recorder: a histogram is
    // about 43KB, and every step in every shard carrying one for a wait almost
    // no step has is memory spent on nothing.
    private var queued: Histogram? = null

    // And what came back under this step, where the module that made the
    // request counted it.
    private var produced = 0L

    val seconds = seconds(width)

    fun record(
        failure: Reason?,
        service: Duration,
        response: Duration,
        at: Duration,
        reached: Boolean = false,
        visit: Boolean = false,
        attempts: Int = 1,
        trace: String? = null,
    ) {
        if (reached) this.reached++
        if (visit) this.visits++
        this.attempts += attempts
        if (failure == null) {
            ok.record(service, response, trace)
        } else {
            failed.record(service, response, trace)
            countFailure(failure, 1L)
        }
        seconds.record(at, failure, service, response)
    }

    fun aside(queued: Duration, produced: Long) {
        if (queued > Duration.ZERO) waits().record(queued)
        this.produced += produced
    }

    private fun waits(): Histogram = queued ?: Histogram().also { queued = it }

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
        visits += other.visits
        attempts += other.attempts
        other.queued?.let { waits().merge(it) }
        produced += other.produced
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
        visits = visits,
        attempts = attempts,
        queued = queued?.timing() ?: Timing.none,
        produced = produced,
        unmatched = outstanding.unmatched,
        inFlight = outstanding.inFlight,
        timeline = seconds.frozen(),
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
 * One coarse histogram per slot of the run so far, from the run's start.
 *
 * The list is grown to reach a slot rather than keyed by one, which is what
 * makes a quiet slot a zero the reader can see instead of a key nobody wrote.
 */
private typealias Seconds = Closing<SecondRecorder, Second>

private fun seconds(width: Width): Seconds = Closing(
    width = width,
    open = ::SecondRecorder,
    fold = SecondRecorder::freeze,
    absorb = SecondRecorder::merge,
    add = { mine, theirs ->
        Second(
            okServiceTime = listOf(mine.okServiceTime, theirs.okServiceTime).merged(),
            failedServiceTime = listOf(mine.failedServiceTime, theirs.failedServiceTime).merged(),
            okResponseTime = listOf(mine.okResponseTime, theirs.okResponseTime).merged(),
            failedResponseTime = listOf(mine.failedResponseTime, theirs.failedResponseTime).merged(),
        )
    },
)

private fun Seconds.record(at: Duration, failure: Reason?, service: Duration, response: Duration) =
    at(at.inWholeSeconds.toInt()).record(failure, service, response)

// Stamped here rather than kept on every slot as it is merged: until the
// freeze, the width is the recorder's and one number.
private fun Seconds.frozen(): List<Second> = freeze().map { it.copy(width = slotWidth.seconds) }

/**
 * How late the departures of each second were, from the run's start.
 *
 * The whole-run [RunRecorder.behind] cannot say *when* a schedule was lost,
 * only that it was, so a reader is told to lower the rate with no idea which
 * rate held. Kept coarse and only at run level: one table a second for the run
 * is a few kilobytes a minute, where one per step per second is what 0025
 * refused to pay for a second time.
 */
private typealias Lateness = Closing<Histogram, Timing>

private fun lateness(width: Width): Lateness = Closing(
    width = width,
    open = Histogram::coarse,
    fold = Histogram::timing,
    absorb = Histogram::merge,
    add = { mine, theirs -> listOf(mine, theirs).merged() },
)

private fun Lateness.record(at: Duration, schedulingDelay: Duration) =
    at(at.inWholeSeconds.toInt()).record(schedulingDelay)

/**
 * How many seconds a slot is, shared by every table of one recorder.
 *
 * A power of two, doubled whenever a second would land past the last of
 * [slots]. The tables catch up when next touched, by merging neighbours.
 */
private class Width(private val slots: Int) {

    // The accumulator AGENTS.md allows a builder: it only grows, and the
    // frozen result carries it as each slot's width.
    var seconds = 1
        private set

    fun reach(second: Int) {
        while (second / seconds >= slots) seconds *= 2
    }

    fun atLeast(other: Int) {
        seconds = maxOf(seconds, other)
    }
}

/**
 * One reading a slot, the first taken in it.
 *
 * Widened by keeping one of each pair rather than averaging them, so every
 * entry is still a count somebody took and `Concurrency` averages readings.
 */
private class Readings(private val width: Width) {

    // The accumulator AGENTS.md allows a builder, frozen into the result.
    private val taken = ArrayList<Long?>()
    private var mine = 1

    fun take(second: Int, users: Long) {
        width.reach(second)
        widen()
        val slot = second / mine
        while (taken.size <= slot) taken.add(null)
        if (taken[slot] == null) taken[slot] = users
    }

    fun merge(other: Readings) {
        other.taken.forEachIndexed { slot, users -> users?.let { take(slot * other.mine, it) } }
    }

    fun freeze(): List<Long?> {
        widen()
        return taken.toList()
    }

    private fun widen() {
        while (mine < width.seconds) {
            val kept = taken.chunked(2) { pair -> pair.firstNotNullOfOrNull { it } }
            taken.clear()
            taken.addAll(kept)
            mine *= 2
        }
    }
}

/**
 * A table a slot, each folded into its frozen form once it is closed.
 *
 * Closed slots are a prefix: what can still land is what departed at or
 * after the oldest departure in flight, so slots are closed in order and a
 * live table is only ever one of the last few. Buckets add, so a slot frozen
 * here and added to later, or merged with its neighbour when the timeline
 * widens, is the slot [RunRecorder.freeze] would have made of it.
 */
private class Closing<R, F>(
    private val width: Width,
    private val open: () -> R,
    private val fold: (R) -> F,
    private val absorb: (R, R) -> Unit,
    private val add: (F, F) -> F,
) {

    // The accumulators AGENTS.md allows a builder, frozen into the result:
    // slots 0 until frozen.size, then the live ones after them.
    private val frozen = ArrayList<F>()
    private val live = ArrayDeque<R>()

    // A record for a slot already closed, which the engine's bound says
    // cannot happen and a body's own sample time can still do.
    private val late = HashMap<Int, R>()

    // How wide this table's slots are now, which trails [width] until touched.
    private var mine = 1

    /** Every second under this is closed, whether or not anything reached it yet. */
    var before = 0
        private set

    val held: Int get() = live.size + late.size

    /** How many seconds a slot is, once this table has caught up. */
    val slotWidth: Int
        get() {
            widen()
            return mine
        }

    // The only allocation on the recording path, and it happens when a slot
    // starts rather than when a request does. A quiet slot below the close
    // is the frozen form of an empty table, so it allocates nothing either.
    fun at(second: Int): R {
        width.reach(second)
        widen()
        val slot = second / mine
        if (slot < frozen.size) return late.getOrPut(slot, open)
        while (frozen.size + live.size <= slot) {
            if (live.isEmpty() && frozen.size < before / mine) frozen.add(quiet) else live.addLast(open())
        }
        return if (slot < frozen.size) late.getOrPut(slot, open) else live[slot - frozen.size]
    }

    private val quiet: F by lazy { fold(open()) }

    fun close(before: Int) {
        widen()
        this.before = maxOf(this.before, before)
        late.forEach { (slot, table) -> frozen[slot] = add(frozen[slot], fold(table)) }
        late.clear()
        while (live.isNotEmpty() && frozen.size < this.before / mine) frozen.add(fold(live.removeFirst()))
    }

    fun merge(other: Closing<R, F>) {
        width.atLeast(other.slotWidth)
        widen()
        // Rounded up to a whole slot of this width, so every slot the other
        // closed lands in one closed here. Closing early costs a late table at
        // most, never a number.
        close((other.before + mine - 1) / mine * mine)
        other.frozen.forEachIndexed { index, theirs ->
            val slot = index * other.mine / mine
            while (frozen.size <= slot) frozen.add(quiet)
            frozen[slot] = add(frozen[slot], theirs)
        }
        other.late.forEach { (slot, table) -> absorb(at(slot * other.mine), table) }
        other.live.forEachIndexed { index, table -> absorb(at((other.frozen.size + index) * other.mine), table) }
        close(before)
    }

    fun freeze(): List<F> {
        widen()
        return frozen.mapIndexed { slot, closed -> late[slot]?.let { add(closed, fold(it)) } ?: closed } +
            live.map(fold)
    }

    private fun widen() {
        while (mine < width.seconds) halve()
    }

    /**
     * Neighbours merged in pairs. A pair of one closed slot and one live
     * table becomes a closed slot with the table beside it as a late one,
     * and the close moves up to cover it.
     */
    private fun halve() {
        val closed = frozen.chunked(2) { pair -> pair.reduce(add) }
        val tables = if (frozen.size % 2 == 1 && live.isNotEmpty()) live.drop(1) else live.toList()
        val straddling = live.firstOrNull()?.takeIf { frozen.size % 2 == 1 }
        val moved = late.entries.map { (slot, table) -> slot / 2 to table }

        frozen.clear()
        frozen.addAll(closed)
        live.clear()
        tables.chunked(2).forEach { pair ->
            pair.drop(1).forEach { absorb(pair[0], it) }
            live.addLast(pair[0])
        }
        late.clear()
        straddling?.let { late[frozen.lastIndex] = it }
        moved.forEach { (slot, table) -> late[slot]?.let { absorb(it, table) } ?: late.put(slot, table) }
        mine *= 2
        before = maxOf(before, frozen.size * mine)
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

    fun record(service: Duration, response: Duration, trace: String? = null) {
        serviceTime.record(service, trace)
        responseTime.record(response, trace)
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
