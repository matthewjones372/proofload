package io.github.matthewjones372.kestrel.engine

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
    val recorders = Recorders(Instant.now())
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
                { scenario.depart(recorders, runStart, departure, users) },
                // Relative to now, but the offset is from the run's start, and
                // booking a million of these is not instant. Subtracting what
                // has already elapsed is what stops every departure inheriting
                // the time spent booking the ones before it.
                departure.inWholeNanoseconds - (System.nanoTime() - runStart),
                TimeUnit.NANOSECONDS,
            )
        }
        users.allScheduled()
        users.awaitAll()
    } finally {
        scheduler.shutdownNow()
    }
    return recorders.freeze()
}

private fun Scenario.depart(recorders: Recorders, runStart: Long, departure: Duration, users: Departures) {
    Thread.ofVirtual().start {
        try {
            // Read here rather than on the scheduler: what the report calls
            // lateness is how late the user's first request left, and until the
            // virtual thread is mounted nothing has left.
            runOneUser(recorders, lateness(runStart, departure))
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
private fun lateness(runStart: Long, departure: Duration): Duration =
    ((System.nanoTime() - runStart).nanoseconds - departure).coerceAtLeast(Duration.ZERO)

// A failed step abandons the user. A null session carries that decision through
// the fold: the steps after it are not run, and are not counted as anything.
// Counting a payment that never had a cart as a success reports a service that
// answered nobody.
private fun Scenario.runOneUser(recorders: Recorders, schedulingDelay: Duration) {
    steps.fold<Step, Session?>(Session.empty) { session, step ->
        session?.let {
            when (step) {
                is Step.Exec -> step.runOn(it, recorders, schedulingDelay)
            }
        }
    }
}

private fun Step.Exec.runOn(session: Session, recorders: Recorders, schedulingDelay: Duration): Session? {
    val startedAt = System.nanoTime()
    val result = attempt(session)
    val serviceTime = (System.nanoTime() - startedAt).nanoseconds
    val reason = result.reason()
    recorders.record(name, reason, serviceTime, schedulingDelay)
    return if (reason == null) result.session else null
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
