package io.github.matthewjones372.kestrel.http

import io.github.matthewjones372.kestrel.Reason

/**
 * The target answered, and with something the step did not ask for.
 *
 * The code rather than the phrase: a reason plotted in a report is the thing a
 * reader groups by, and two gateways spelling 503 differently would be two rows
 * saying one thing.
 */
data class HttpStatus(val code: Int) : Reason {
    override val described: String get() = "status $code"
}

/**
 * A check the caller declared, by the name they gave it.
 *
 * The name is the whole point: a report saying `has an order id` sends someone
 * to the right place, and one saying `check failed` sends them to all of them.
 */
data class CheckFailed(val check: String) : Reason {
    override val described: String get() = check
}

/**
 * A capture that found nothing, named for the key it was meant to fill.
 *
 * Failed here rather than three steps later at the path that would have used
 * it: a capture that quietly does nothing surfaces as somebody else's problem.
 */
data class NothingCaptured(val key: String) : Reason {
    override val described: String get() = "no $key captured"
}

/** A `{name}` in a path the session had nothing under. */
data class UnfilledPath(val placeholder: String) : Reason {
    override val described: String get() = "missing {$placeholder}"
}
