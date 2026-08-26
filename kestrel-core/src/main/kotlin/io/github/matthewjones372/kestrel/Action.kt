package io.github.matthewjones372.kestrel

/**
 * What one step does. Core declares it without a protocol type, so an HTTP
 * module can implement it without core growing a dependency on a client.
 */
fun interface Action {
    fun run(session: Session): StepResult
}

/**
 * The outcome of one step, as a value. An engine measures a failure by reading
 * this rather than by catching, so a target's error response and a bug in the
 * generator do not arrive by the same route.
 */
sealed interface StepResult {

    val session: Session

    data class Ok(override val session: Session) : StepResult

    data class Failed(override val session: Session, val reason: String) : StepResult
}

fun Session.ok(): StepResult = StepResult.Ok(this)

fun Session.failed(reason: String): StepResult = StepResult.Failed(this, reason)
