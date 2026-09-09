package io.github.matthewjones372.proofload.http

import io.github.matthewjones372.proofload.Reason

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
 * The target answered with a status the caller said this endpoint can answer
 * with — a documented `404`, a declared `409`.
 *
 * Still a failure: a request that did not do what the step asked for is not a
 * success, and counting it as one would inflate the goodput of a run against a
 * service returning nothing but declared errors. Its own reason so a reader can
 * tell it apart from [HttpStatus], which is the service doing something nobody
 * wrote down. That distinction is the one a load test otherwise has to be told
 * by hand, per step, in every tool that has it at all.
 */
data class DeclaredStatus(val code: Int) : Reason {
    override val described: String get() = "status $code, declared"
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

/**
 * The target kept redirecting past the number of hops the request allowed.
 *
 * The limit rather than the last URL it offered: a redirect loop names a
 * different location every time round, and a report keyed on those is a row per
 * request saying the one thing this says once.
 */
data class TooManyRedirects(val max: Int) : Reason {
    override val described: String get() = "more than $max redirects"
}

/** A `{name}` in a path the session had nothing under. */
data class UnfilledPath(val placeholder: String) : Reason {
    override val described: String get() = "missing {$placeholder}"
}
