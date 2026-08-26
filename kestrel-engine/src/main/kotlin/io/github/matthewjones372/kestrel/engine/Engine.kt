package io.github.matthewjones372.kestrel.engine

import io.github.matthewjones372.kestrel.RunRecorder
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.Scenario
import io.github.matthewjones372.kestrel.Session
import io.github.matthewjones372.kestrel.Simulation
import io.github.matthewjones372.kestrel.Step
import io.github.matthewjones372.kestrel.StepResult
import io.github.matthewjones372.kestrel.departures
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/** Sends the simulation and blocks until the last user it started has finished. */
fun Simulation.run(): RunResult {
    val recorder = RunRecorder(Instant.now())
    val runStart = System.nanoTime()
    val users = Departures()
    // One platform thread. Its only job is to start virtual threads at the
    // offsets the profile named; a step never runs on it, so a slow target
    // cannot push a departure back.
    val scheduler = Executors.newSingleThreadScheduledExecutor(::schedulerThread)
    try {
        profile.departures().forEach { departure ->
            users.starting()
            scheduler.schedule(
                { scenario.depart(recorder, runStart, departure, users) },
                departure.inWholeNanoseconds,
                TimeUnit.NANOSECONDS,
            )
        }
        users.allScheduled()
        users.awaitAll()
    } finally {
        scheduler.shutdownNow()
    }
    return recorder.freeze()
}

private fun Scenario.depart(recorder: RunRecorder, runStart: Long, departure: Duration, users: Departures) {
    Thread.ofVirtual().start {
        try {
            // Read here rather than on the scheduler: what the report calls
            // lateness is how late the user's first request left, and until the
            // virtual thread is mounted nothing has left.
            runOneUser(recorder, lateness(runStart, departure))
        } finally {
            users.finished()
        }
    }
}

private fun schedulerThread(runnable: Runnable): Thread =
    Thread(runnable, "kestrel-scheduler").apply { isDaemon = true }

/**
 * How many users are still to finish, and a gate that opens when none are.
 * The scheduling loop counts as one of them until the last departure is
 * booked, so a run whose early users finish before its late ones are even
 * scheduled cannot declare itself over.
 */
private class Departures {

    private val outstanding = AtomicLong(1)
    private val allFinished = CountDownLatch(1)

    fun starting() {
        outstanding.incrementAndGet()
    }

    fun finished() {
        if (outstanding.decrementAndGet() == 0L) allFinished.countDown()
    }

    fun allScheduled() = finished()

    fun awaitAll() = allFinished.await()
}

/**
 * How late a user left against the departure its profile named. Floored at
 * zero: a scheduler cannot fire early, and a user that started before its
 * offset is not a backlog anybody can act on.
 */
internal fun lateness(runStart: Long, departure: Duration): Duration =
    ((System.nanoTime() - runStart).nanoseconds - departure).coerceAtLeast(Duration.ZERO)

internal fun Scenario.runOneUser(recorder: RunRecorder, schedulingDelay: Duration) {
    steps.fold(Session.empty) { session, step ->
        when (step) {
            is Step.Exec -> step.runOn(session, recorder, schedulingDelay)
        }
    }
}

private fun Step.Exec.runOn(session: Session, recorder: RunRecorder, schedulingDelay: Duration): Session {
    val startedAt = System.nanoTime()
    val result = attempt(session)
    val serviceTime = (System.nanoTime() - startedAt).nanoseconds
    // A `RunRecorder` is a mutable accumulator that says it is not thread-safe,
    // and every virtual user shares this one. The lock is outside the timed
    // region above, so it costs throughput rather than latency; sharding it is
    // the next entry.
    synchronized(recorder) { recorder.record(name, result.reason(), serviceTime, schedulingDelay) }
    return result.session
}

// The one place in the library allowed to catch a throwable. An action is code
// the caller wrote against a target the caller does not control, so a throw out
// of it is a request that failed, to be measured and named — not a bug in the
// engine and not a reason to lose the rest of the run.
private fun Step.Exec.attempt(session: Session): StepResult = try {
    action.run(session)
} catch (throwable: Throwable) {
    StepResult.Failed(session, throwable.javaClass.name)
}

private fun StepResult.reason(): String? = when (this) {
    is StepResult.Ok -> null
    is StepResult.Failed -> reason
}
