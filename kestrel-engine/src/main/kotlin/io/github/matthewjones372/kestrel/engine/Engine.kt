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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/** Sends the simulation and blocks until the last user it started has finished. */
fun Simulation.run(): RunResult {
    val recorder = RunRecorder(Instant.now())
    val runStart = System.nanoTime()
    profile.departures().forEach { departure ->
        scenario.runOneUser(recorder, lateness(runStart, departure))
    }
    return recorder.freeze()
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
    recorder.record(name, result.reason(), (System.nanoTime() - startedAt).nanoseconds, schedulingDelay)
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
