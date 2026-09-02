package io.github.matthewjones372.kestrel

import kotlin.time.Duration

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

    /**
     * Whether the body recorded its own samples, in which case the engine
     * records none for the step: a body that measured a hundred messages has
     * already said what it saw.
     */
    val sampled: Boolean

    data class Ok(
        override val session: Session,
        override val attempts: Int = 1,
        override val trace: String? = null,
        override val sampled: Boolean = false,
    ) : StepResult

    data class Failed(
        override val session: Session,
        val reason: Reason,
        override val attempts: Int = 1,
        override val trace: String? = null,
        override val sampled: Boolean = false,
    ) : StepResult
}

/**
 * Where a step body's own samples go, so a body that observes several answers
 * records several rather than one.
 *
 * Named for the sink rather than for the samples: `Samples` is already a
 * population of runs in [Difference].
 *
 * In core because [StepScope] is, and an engine in any module supplies it. An
 * engine that supplies none — or a caller running a step outside a run — gets
 * a scope that reports one sample for the whole body, which is every step
 * written before this existed.
 */
fun interface SampleSink {

    /**
     * One answer under this step's name: it took [took], and was observed
     * [at] into the run, or now where the body does not know.
     */
    fun sample(took: Duration, at: Duration?, reason: Reason?)
}

/**
 * What a step body runs against. The session is threaded here rather than by
 * the caller, and the accumulator is frozen into a `StepResult` the moment the
 * body returns.
 */
class StepScope(session: Session, private val samples: SampleSink? = null) {

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

    // Whether the body reported its own samples. The engine records one for
    // the whole body only when it did not: a step that measured a hundred
    // messages and then had a hundred-and-first recorded over it would report
    // a latency nobody saw.
    private var sampled = false

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
     * One answer this body observed: [took] long, [at] into the run where the
     * body knows and now where it does not, failed with [reason] where it did.
     *
     * A body that calls this is measuring its own answers — a stream of
     * messages, the attempts behind a retry — and the engine then records none
     * of its own for the step, because a sample over the whole body would be a
     * latency nobody experienced. The step is still one row and the users that
     * reached it are still counted once.
     */
    fun sample(took: Duration, at: Duration? = null, reason: Reason? = null) {
        sampled = true
        samples?.sample(took, at, reason)
    }

    /**
     * What the body reported, frozen.
     *
     * Public because [Engine] is: an engine in another module builds the scope
     * a step reports through and reads the outcome off it, and 0051 put that
     * seam in core precisely so one could.
     */
    fun result(): StepResult =
        reason?.let { StepResult.Failed(session, it, attempts, trace, sampled) }
            ?: StepResult.Ok(session, attempts, trace, sampled)
}

/** A step body as a value, so one action can be shared by several scenarios. */
fun action(block: StepScope.() -> Unit): Action = Action { scope -> scope.block() }
