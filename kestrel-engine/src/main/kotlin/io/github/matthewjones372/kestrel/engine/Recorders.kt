package io.github.matthewjones372.kestrel.engine

import io.github.matthewjones372.kestrel.RunRecorder
import io.github.matthewjones372.kestrel.RunResult
import java.time.Instant
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.time.Duration

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
internal class Recorders(private val startedAt: Instant, shards: Int = defaultShards) {

    // The mutable accumulator this whole class is about: the alternative is an
    // allocation per request on the path the report calls the target's latency.
    private val slots: AtomicReferenceArray<RunRecorder?> =
        AtomicReferenceArray<RunRecorder?>(shards)
            .also { array -> repeat(shards) { index -> array.set(index, RunRecorder(startedAt)) } }

    fun record(step: String, failure: String?, serviceTime: Duration, schedulingDelay: Duration) {
        // The thread id spreads consecutive users across slots; it is a
        // starting guess, not an assignment.
        val from = (Thread.currentThread().threadId() % slots.length()).toInt()
        recordFrom(from, step, failure, serviceTime, schedulingDelay)
    }

    fun freeze(): RunResult {
        val merged = RunRecorder(startedAt)
        repeat(slots.length()) { index ->
            merged.merge(checkNotNull(slots.get(index)) { "a shard was still in use when the run ended" })
        }
        return merged.freeze()
    }

    private tailrec fun recordFrom(
        index: Int,
        step: String,
        failure: String?,
        serviceTime: Duration,
        schedulingDelay: Duration,
    ) {
        val recorder = slots.getAndSet(index, null)
        if (recorder != null) {
            try {
                recorder.record(step, failure, serviceTime, schedulingDelay)
            } finally {
                slots.set(index, recorder)
            }
            return
        }
        Thread.onSpinWait()
        recordFrom((index + 1) % slots.length(), step, failure, serviceTime, schedulingDelay)
    }

    companion object {
        /** As many as can be running at once, which is as many as are ever needed. */
        val defaultShards: Int get() = Runtime.getRuntime().availableProcessors()
    }
}
