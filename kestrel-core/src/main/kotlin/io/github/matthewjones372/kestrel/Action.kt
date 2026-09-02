package io.github.matthewjones372.kestrel

/**
 * What one step does. Core declares it without a protocol type, so an HTTP
 * module can implement it without core growing a dependency on a client.
 */
fun interface Action {

    /**
     * Does the step, reporting through [scope] rather than returning.
     *
     * The scope is handed in rather than made here because it is the run's,
     * not the action's: it carries the session, and it is where a body says
     * what it did. An action that made its own could report nothing the engine
     * had not asked for, which is what stops a step body recording more than
     * one sample, retrying without burying the retry, or telling a trace what
     * it sent.
     */
    fun run(scope: StepScope)

    /**
     * The step against a session, on a scope of its own, for a caller that has
     * one and wants the outcome — a test, or anything that is not an engine.
     *
     * An engine does not use this: it builds one scope, runs the step through
     * it and reads the result off it, because the scope is where a body
     * reports and the engine is what asked.
     */
    fun run(session: Session): StepResult = StepScope(session).also(::run).result()
}

/**
 * The outcome of one step, as a value. An engine measures a failure by reading
 * this rather than by catching, so a target's error response and a bug in the
 * generator do not arrive by the same route.
 */
sealed interface StepResult {

    val session: Session

    /**
     * How many times this step went to the target to produce one measurement:
     * one for an ordinary request, more where it followed a redirect or
     * retried. One request is still one sample; this counts the round trips
     * behind it, so a report can say 480 requests and 512 attempts rather than
     * quietly reporting the target as slower than it is.
     */
    val attempts: Int

    /**
     * A trace id this step sent, where it traced: what the report points at
     * when a reader asks to see a request that landed at p99. Null where the
     * step traced nothing, which is every untraced run.
     */
    val trace: String?

    data class Ok(
        override val session: Session,
        override val attempts: Int = 1,
        override val trace: String? = null,
    ) : StepResult

    data class Failed(
        override val session: Session,
        val reason: Reason,
        override val attempts: Int = 1,
        override val trace: String? = null,
    ) : StepResult
}

/**
 * What a step body runs against. The session is threaded here rather than by
 * the caller, and the accumulator is frozen into a `StepResult` the moment the
 * body returns.
 */
class StepScope(session: Session) {

    /**
     * The session as it stands, readable for a body that needs the whole of it
     * — a correlation key computed from several values — and writable only
     * through [set], so what a step reads back is what it put there.
     */
    var session: Session = session
        private set

    // The builder case again: a body that goes to the target more than once
    // counts here, and the count is frozen into the StepResult with the rest.
    private var attempts = 1

    private var trace: String? = null

    // The builder case AGENTS.md allows: a step body is written as statements,
    // so the session and the reason accumulate across them and are frozen into
    // a StepResult the moment the body returns. Neither escapes mutable.
    private var reason: Reason? = null

    operator fun <T : Any> get(key: SessionKey<T>): T? = session[key]

    fun <T : Any> set(key: SessionKey<T>, value: T) {
        session = session.set(key, value)
    }

    /**
     * Marks the step failed and returns; it does not stop the body. Throwing
     * to report a declared failure would put a second error model beside this
     * one, and the engine would have to catch to measure.
     */
    fun fail(reason: Reason) {
        // First reason wins: a timeout that follows a 503 is the 503's doing,
        // and a report that renames it loses which one to go and fix.
        if (this.reason == null) this.reason = reason
    }

    /**
     * The same, for a step body with no type to give: a library that answers
     * with a message, or a condition nobody has named yet. Recorded as [Said],
     * so it groups like every other reason.
     */
    fun fail(reason: String) = fail(Said(reason))

    /**
     * Says this body went to the target once more.
     *
     * For a hop or a retry: the step is still one request with one service
     * time, and this is the count of round trips underneath it.
     */
    fun attempted() {
        attempts++
    }

    /**
     * Says this step sent [id] as its trace, so a percentile can point at one
     * of the requests behind it.
     *
     * The last one wins where a step made several trips: a redirect chain's
     * last hop is the one whose latency the step reports.
     */
    fun traced(id: String) {
        trace = id
    }

    /**
     * What the body reported, frozen.
     *
     * Public because [Engine] is: an engine in another module builds the scope
     * a step reports through and reads the outcome off it, and 0051 put that
     * seam in core precisely so one could.
     */
    fun result(): StepResult =
        reason?.let { StepResult.Failed(session, it, attempts, trace) }
            ?: StepResult.Ok(session, attempts, trace)
}

/** A step body as a value, so one action can be shared by several scenarios. */
fun action(block: StepScope.() -> Unit): Action = Action { scope -> scope.block() }
