package io.github.matthewjones372.proofload

/**
 * Why a step failed, as a value rather than as text.
 *
 * Open rather than sealed, because the set genuinely is: the module that knows
 * what a failure means is the module that made the request, and a caller's own
 * step is as entitled to name one as anything here. So a `when` over a reason
 * takes an `else`, and that `else` is honest — there is a reason out there this
 * file has not heard of — rather than the one over a sealed type that
 * `AGENTS.md` forbids, which is a case somebody forgot.
 *
 * **An implementation must be a value**: a data class or an object. A reason is
 * a key in [Outcome.reasons], merged across recording shards and again across
 * runs, so one with identity equality gets a row per request instead of a
 * count. Every reason this repository ships is one, and a caller who writes a
 * plain class sees the mistake in the first report.
 */
interface Reason {

    /** What a report prints, and what a reader recognises it by. */
    val described: String
}

/**
 * A reason a step body named in words, and what [StepScope.fail] records when
 * it is handed a string.
 *
 * The escape hatch, deliberately: a step calling into a library that answers
 * with a message has no type to give, and inventing one per message would be
 * the row-per-request this design is built to avoid.
 */
data class Said(val text: String) : Reason {
    override val described: String get() = text
}

/**
 * Something thrown on the path, by the class it was rather than the message it
 * carried.
 *
 * The message is what a reader wants and what a report cannot afford: it
 * carries a host, a port and often an id, so a message is a row per request,
 * while `ConnectException` is one row that says the same thing.
 */
data class Threw(val type: String) : Reason {
    override val described: String get() = type
}

/**
 * The target was reachable and did not answer inside the time the step was
 * given.
 *
 * Named rather than classed, because it is the one failure a reader acts on
 * differently: a refused connection is a service that is down, and this is one
 * that is up and too slow.
 */
data object TimedOut : Reason {
    override val described: String get() = "timeout"
}

/**
 * The reasons past [RunRecorder.MAX_REASONS_PER_STEP], counted together.
 *
 * A reason with an id in it makes a key per request, and a report that listed
 * them all would be a list of one-offs. What this loses is which ones; what it
 * keeps is that there were more, which is the part a reader acts on.
 */
data object Other : Reason {
    override val described: String get() = "other"
}
