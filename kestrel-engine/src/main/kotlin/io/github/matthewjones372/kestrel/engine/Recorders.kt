package io.github.matthewjones372.kestrel.engine

import io.github.matthewjones372.kestrel.Arrivals
import io.github.matthewjones372.kestrel.Outstanding
import io.github.matthewjones372.kestrel.Plan
import io.github.matthewjones372.kestrel.Reason
import io.github.matthewjones372.kestrel.RunRecorder
import io.github.matthewjones372.kestrel.RunResult
import java.time.Instant
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.time.Duration

/**
 * What a step reports to when it finishes. The walk holds one of these rather
 * than a [Recorders], so a trace can print from the same walk a run measures
 * without a branch on the path a request is timed on.
 */
internal fun interface StepSink {

    // Past detekt's limit, and deliberately, for the reason recordFrom below
    // gives: a value holding these is an allocation per request on the path
    // whose delay this tool would otherwise report as the target's latency.
    @Suppress("LongParameterList")
    fun record(
        step: String,
        failure: Reason?,
        serviceTime: Duration,
        schedulingDelay: Duration,
        at: Duration,
        reached: Boolean,
        visit: Boolean,
        attempts: Int,
        trace: String?,
    )

    /**
     * What a step reported about itself rather than about its answer: how long
     * it [queued] for a resource this end of the wire owns, and how much the
     * target [produced].
     *
     * A default of nothing rather than a second abstract method, so a sink
     * written as a lambda — the trace printer, the one that records nothing —
     * stays a lambda. Only a step with something to say reaches it.
     */
    fun aside(step: String, queued: Duration, produced: Long) {}
}

/**
 * One recorder per processor, shared by every virtual user and merged when the
 * run ends.
 *
 * A `RunRecorder` keeps two histograms per step at about 43KB each, so one per
 * virtual user is gigabytes for a run that is meant to be cheap; and one shared
 * between them all is a lock on the path being timed. What is wanted is one per
 * *carrier* thread, because only as many users can be running at once as there
 * are carriers to run them.
 *
 * The JDK exposes no way to ask which carrier a virtual thread is mounted on,
 * so a shard is claimed rather than looked up: the writer takes a recorder out
 * of its slot with a single compare-and-set, uses it, and puts it back. A
 * virtual thread cannot unmount in between — nothing on that path blocks — so
 * the claim is uncontended in the common case, and it stays correct rather than
 * merely lucky if one ever does.
 */
internal class Recorders(
    startedAt: Instant,
    shards: Int = defaultShards,
    /** False for a closed run, which promised no departures and so records no lateness. */
    keepingSchedule: Boolean = true,
) : StepSink {

    // One origin for the run, spawned rather than read again per shard: shards
    // are merged into one timeline at the end, and three recorders that each
    // read the clock are three zero points that pool into a smear.
    private val first = RunRecorder(startedAt, keepingSchedule)

    // The mutable accumulator this whole class is about: the alternative is an
    // allocation per request on the path the report calls the target's latency.
    private val slots: AtomicReferenceArray<RunRecorder?> =
        AtomicReferenceArray<RunRecorder?>(shards)
            .also { array -> repeat(shards) { index -> array.set(index, first.shard()) } }

    private val completions = first.shard()

    // One counter per shard, written only by the thread holding that shard and
    // read approximately by the ticker. Single-writer, so a plain volatile
    // store is enough and no reader can tear one; adding them up is the
    // ticker's work, off the path being timed. A shared counter here would be
    // a contended increment per request, which is the thing this whole class
    // is arranged to avoid.
    private val counted = AtomicLongArray(shards)

    private val failures = AtomicLongArray(shards)

    /** What has been recorded so far, added up without stopping anything. */
    fun counts(): Pair<Long, Long> {
        var requests = 0L
        var failed = 0L
        repeat(counted.length()) { index ->
            requests += counted.get(index)
            failed += failures.get(index)
        }
        return requests to failed
    }

    /** How long the run has been going, for a caller that was told no offset. */
    fun sinceStart(): Duration = first.sinceStart()

    @Suppress("LongParameterList")
    override fun record(
        step: String,
        failure: Reason?,
        serviceTime: Duration,
        schedulingDelay: Duration,
        at: Duration,
        reached: Boolean,
        visit: Boolean,
        attempts: Int,
        trace: String?,
    ) {
        // The thread id spreads consecutive users across slots; it is a
        // starting guess, not an assignment.
        val from = (Thread.currentThread().threadId() % slots.length()).toInt()
        recordFrom(from, step, failure, serviceTime, schedulingDelay, at, reached, visit, attempts, trace)
    }

    override fun aside(step: String, queued: Duration, produced: Long) {
        val from = (Thread.currentThread().threadId() % slots.length()).toInt()
        asideFrom(from, step, queued, produced)
    }

    /** A shard claimed the way [recordFrom] claims one, and moved on from for the same reason. */
    private tailrec fun asideFrom(index: Int, step: String, queued: Duration, produced: Long) {
        val claimed = slots.getAndSet(index, null)
        if (claimed != null) {
            try {
                claimed.aside(step, queued, produced)
            } finally {
                slots.set(index, claimed)
            }
            return
        }
        Thread.onSpinWait()
        asideFrom((index + 1) % slots.length(), step, queued, produced)
    }

    /**
     * The answers a sink gave, written by the one thread that drains it. It
     * claims no shard: there is only ever the one writer, and it is not on the
     * path a step is timed on.
     */
    fun arrived(step: String, latency: Duration, at: Duration) = completions.arrived(step, latency, at)

    fun outstanding(step: String, outstanding: Outstanding) = completions.outstanding(step, outstanding)

    fun freeze(plan: Plan, arrivals: Arrivals): RunResult {
        val merged = first.shard()
        repeat(slots.length()) { index ->
            merged.merge(checkNotNull(slots.get(index)) { "a shard was still in use when the run ended" })
        }
        merged.merge(completions)
        return merged.freeze().copy(plan = plan, arrivals = arrivals)
    }

    // Several parameters past the limit, and deliberately: the alternative is a
    // value holding them, which is an allocation per request on the very path
    // this class exists to keep allocation off.
    @Suppress("LongParameterList")
    private tailrec fun recordFrom(
        index: Int,
        step: String,
        failure: Reason?,
        serviceTime: Duration,
        schedulingDelay: Duration,
        at: Duration,
        reached: Boolean,
        visit: Boolean,
        attempts: Int,
        trace: String?,
    ) {
        val recorder = slots.getAndSet(index, null)
        if (recorder != null) {
            try {
                recorder.record(step, failure, serviceTime, schedulingDelay, at, reached, visit, attempts, trace)
                // Lazy: this thread owns the slot, so nothing else writes these
                // two, and a ticker reading a count one store stale is what a
                // watcher is for. An ordered store would cost a fence per
                // request to make a progress line a moment fresher.
                counted.lazySet(index, counted.get(index) + 1)
                if (failure != null) failures.lazySet(index, failures.get(index) + 1)
            } finally {
                slots.set(index, recorder)
            }
            return
        }
        Thread.onSpinWait()
        recordFrom(
            (index + 1) % slots.length(),
            step,
            failure,
            serviceTime,
            schedulingDelay,
            at,
            reached,
            visit,
            attempts,
            trace,
        )
    }

    companion object {
        /** As many as can be running at once, which is as many as are ever needed. */
        val defaultShards: Int get() = Runtime.getRuntime().availableProcessors()
    }
}
