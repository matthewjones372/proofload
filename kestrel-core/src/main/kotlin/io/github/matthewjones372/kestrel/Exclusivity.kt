package io.github.matthewjones372.kestrel

import java.time.Instant

/**
 * Where a run stands in the queue for the machine it is about to measure.
 *
 * A value with no clock and no lock in it, so the protocol reads and tests
 * without either. [Waiting] is a state of its own rather than a flag, so a run
 * that queued for four minutes is something a report can be made to say instead
 * of time nobody accounted for.
 */
sealed interface Exclusivity {

    /** Nothing is being measured on this machine, so far as this JVM knows. */
    data object Idle : Exclusivity

    /** Somebody else holds the machine, and this run has been waiting [since]. */
    data class Waiting(val since: Instant) : Exclusivity

    /** Measuring what the machine can resolve, which is a run of a null step. */
    data object Calibrating : Exclusivity

    /** Sending a simulation. */
    data object Running : Exclusivity

    /** Sending nothing and waiting for what was sent to come back; 0040's window. */
    data object Draining : Exclusivity
}

/**
 * Whether [next] can follow. Work is only ever entered from [Exclusivity.Idle]
 * or [Exclusivity.Waiting], the two states in which the machine has just been
 * granted, so starting a run without holding it is a step nothing can take.
 */
fun Exclusivity.leadsTo(next: Exclusivity): Boolean = when (this) {
    Exclusivity.Idle -> next is Exclusivity.Waiting || next.isWork()
    is Exclusivity.Waiting -> next.isWork()
    Exclusivity.Calibrating -> next == Exclusivity.Idle
    Exclusivity.Running -> next == Exclusivity.Draining || next == Exclusivity.Idle
    Exclusivity.Draining -> next == Exclusivity.Idle
}

/**
 * [next], having checked the machine can get there from here.
 *
 * Throwing rather than answering with a failure: nobody was promised a
 * transition they could not take, so one is a bug in whatever asked.
 */
fun Exclusivity.then(next: Exclusivity): Exclusivity {
    check(leadsTo(next)) { "a run cannot go from $this to $next" }
    return next
}

private fun Exclusivity.isWork(): Boolean = this == Exclusivity.Running || this == Exclusivity.Calibrating
