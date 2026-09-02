package io.github.matthewjones372.kestrel.grpc

import io.github.matthewjones372.kestrel.Action
import io.github.matthewjones372.kestrel.Outstanding
import io.github.matthewjones372.kestrel.Pending
import io.github.matthewjones372.kestrel.Reason
import io.github.matthewjones372.kestrel.ScenarioBuilder
import io.github.matthewjones372.kestrel.SessionKey
import io.github.matthewjones372.kestrel.StepName
import io.github.matthewjones372.kestrel.StepScope
import io.github.matthewjones372.kestrel.Threw
import io.github.matthewjones372.kestrel.TimedOut
import io.github.matthewjones372.kestrel.sessionKey
import io.grpc.MethodDescriptor
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * A step that needed a stream nothing had opened: an `awaiting` or a `send`
 * with no `open` before it, or one whose `open` failed.
 */
data object NotStreaming : Reason {
    override val described: String get() = "not streaming"
}

/** The far end completed the call before the answers a step was waiting for arrived. */
data object StreamEnded : Reason {
    override val described: String get() = "stream ended"
}

/** Where one user's open stream lives while their journey runs. */
val streaming: SessionKey<Stream<*, *>> = sessionKey("kestrel.grpc.stream")

/**
 * One user's open call.
 *
 * Per user rather than shared, which is the opposite of the channel and for
 * the opposite reason: a stream test is about how many calls a target holds
 * open, so sharing one would remove what is being measured. The channel
 * underneath is still one pool.
 */
class Stream<Q, A> internal constructor(
    internal val requests: StreamObserver<Q>,
    internal val answers: Answers<A>,
) {

    /** Answers that came back to a message this stream had outstanding. */
    val matched: Long get() = answers.matched

    /** Answers nobody sent for, counted and not timed: there is no departure to measure one from. */
    val unsolicited: Long get() = answers.unsolicited

    /**
     * Messages this stream has still to see an answer to, split by whether
     * they were given [window] to be answered in — one sent too late to have
     * had it is still moving rather than lost.
     */
    fun outstanding(window: Duration): Outstanding = answers.outstanding(window)
}

/**
 * Opens [descriptor] as a stream, and puts it in this user's session.
 *
 * [open] is the caller's own stub call: it is handed the observer this module
 * will read the answers on, and returns the one the caller writes requests to.
 * The seam is under the stub, so a generated bidirectional or client-streaming
 * stub goes in unchanged.
 *
 * A unary descriptor is refused here as a streaming one is refused by `call`:
 * one round trip is one sample and does not need any of this.
 */
fun <Q, A> Grpc.stream(
    descriptor: MethodDescriptor<Q, A>,
    open: (StreamObserver<A>) -> StreamObserver<Q>,
): GrpcStream<Q, A> {
    require(descriptor.type != MethodDescriptor.MethodType.UNARY) {
        "${descriptor.fullMethodName} is unary: `call` measures it as the one round trip it is"
    }
    return GrpcStream(descriptor, this, open)
}

/** The action `open` runs: starting the call is the thing being timed. */
class GrpcStream<Q, A> internal constructor(
    private val descriptor: MethodDescriptor<Q, A>,
    private val origin: Grpc,
    private val open: (StreamObserver<A>) -> StreamObserver<Q>,
) : Action {

    val name: String get() = descriptor.fullMethodName

    override fun run(scope: StepScope) {
        if (scope.narrating) scope.note("${descriptor.type} ${descriptor.fullMethodName} to ${origin.target}")
        val answers = Answers<A>()
        val requests = try {
            tellingScope(scope) { open(answers) }
        } catch (refused: StatusRuntimeException) {
            return scope.fail(refused.status.asStreamReason())
        } catch (refused: StatusException) {
            return scope.fail(refused.status.asStreamReason())
        }
        scope.set(streaming, Stream(requests, answers))
    }
}

/** Names the step for the method, as a unary call is named. */
fun ScenarioBuilder.open(stream: GrpcStream<*, *>) {
    exec(stream.name, stream)
}

/**
 * One message out, as a departure that does not wait.
 *
 * The sample is the write alone: it starts when the message is handed to the
 * stub's observer and ends when that returns. No answer is waited for here —
 * an answer arrives later on the same call, and `awaiting` is where it is
 * matched to the message it answers.
 */
fun <Q> ScenarioBuilder.send(name: StepName, message: Q) {
    exec(name, Action { scope -> scope.sendOn(message) })
}

@Suppress("UNCHECKED_CAST")
private fun <Q> StepScope.sendOn(message: Q) {
    val open = this[streaming] as Stream<Q, *>? ?: return fail(NotStreaming)
    // Registered before the write rather than after it: a quick target can
    // answer while the observer's own call is still returning, and an answer
    // that finds nothing outstanding is counted as a message nobody asked for.
    open.answers.departed()
    try {
        open.requests.onNext(message)
    } catch (refused: StatusRuntimeException) {
        open.answers.aborted()
        fail(refused.status.asStreamReason())
    } catch (refused: StatusException) {
        open.answers.aborted()
        fail(refused.status.asStreamReason())
    }
}

/**
 * [count] answers to messages this stream has already sent.
 *
 * One sample per answer, each measured from the message it answers rather than
 * from the step: `awaiting(count = 100)` is a hundred samples under this name,
 * so the distribution of the messages is what the report draws. The users that
 * reached the step are still counted once.
 *
 * An answer is paired with the message at the head of the queue, which on one
 * call is the order they were written in. An answer arriving with nothing
 * outstanding answers no message: it is counted as unsolicited and not timed,
 * because there is no departure to measure it from. A server-streaming call is
 * therefore not what this measures — its messages answer no send of their own,
 * and timing them from the call that opened it climbs with the index while
 * timing them from each other is cadence, which is a different number under a
 * different name.
 */
fun ScenarioBuilder.awaiting(name: StepName, count: Int, within: Duration) {
    require(count > 0) { "a step cannot wait for $count answers" }
    exec(name) { awaitOn(count.toLong(), within) }
}

private fun StepScope.awaitOn(count: Long, within: Duration) {
    val open = this[streaming] ?: return fail(NotStreaming)
    // The wait is bounded and happens on the user's own virtual thread, which
    // unmounts while it blocks: a target that goes quiet costs a carrier
    // nothing, and is a failure rather than a user parked for the rest of the
    // run.
    val why = open.answers.awaitMatched(count, within)
    // Reported whether the wait succeeded or not: the answers that did arrive
    // were measured, and a step that timed out after ninety of a hundred has
    // ninety real latencies to show beside the failure.
    open.answers.takeAnswered().forEach { sample(it) }
    why?.let { reason ->
        // The failure is a sample of its own, timed from the last answer that
        // did arrive: how long the one that never came had been outstanding.
        // Without it the failure would not be recorded at all — a body that
        // reports its own samples is not given one by the engine, and the
        // reason rides a sample. Ninety good answers and a silent success is
        // the wrong report to leave behind.
        sample(open.answers.sinceLastAnswer(), reason = reason)
        fail(reason)
    }
}

/** Closes the writing half, which is what a client-streaming call ends with. */
fun ScenarioBuilder.done(name: StepName) {
    exec(name, Action { scope -> scope.doneSending() })
}

private fun StepScope.doneSending() {
    val open = this[streaming] ?: return fail(NotStreaming)
    open.requests.onCompleted()
}

/**
 * The answers of one call: what arrives, and the messages still owed one.
 *
 * Shared between the user that sends and gRPC's own thread that reads the
 * call, so it is the one structure here that is thread-safe. Nothing on
 * gRPC's thread touches a recorder — the latencies are computed here and
 * drained on the user's own thread — because the recorder is sharded per user
 * rather than locked.
 */
class Answers<A> internal constructor() : StreamObserver<A> {

    private val origin = System.nanoTime()

    private val ended = CountDownLatch(1)

    private val pending = Pending()

    // Which message an answer belongs to is the head of this queue rather than
    // anything read out of it: answers arrive on one call in the order the
    // messages were written, and this module parses no payloads.
    private val waiting = ConcurrentLinkedQueue<Long>()

    private val sent = AtomicLong()

    private val paired = AtomicLong()

    private val unasked = AtomicLong()

    // When the last answer arrived, on this call's own clock.
    private val lastAnswer = AtomicLong()

    private val answered = ConcurrentLinkedQueue<Duration>()

    // What a step has already waited for, touched by that user's thread alone:
    // two `awaiting` steps in one scenario wait for their own answers rather
    // than both being satisfied by the first one's.
    private val awaited = AtomicLong()

    private val gate = ReentrantLock()

    private val arrived = gate.newCondition()

    internal val matched: Long get() = paired.get()

    internal val unsolicited: Long get() = unasked.get()

    internal fun departed() {
        val id = sent.incrementAndGet()
        val at = elapsed()
        pending.departed(id, intended = at, at = at)
        waiting.add(id)
    }

    /**
     * A message that never left. Its departure is claimed back, so a failed
     * write is a failed step rather than that and an answer nobody sent.
     */
    internal fun aborted() {
        waiting.poll()?.let { pending.observed(it, elapsed()) }
    }

    internal fun takeAnswered(): List<Duration> = generateSequence { answered.poll() }.toList()

    /**
     * How long since the last answer arrived, or since the call opened when
     * none has.
     *
     * What an answer that never came had been outstanding for, which is the
     * only duration a failed wait can honestly be given: from the step's start
     * it would be the whole batch, and that is a number about the batch rather
     * than about the answer that is missing.
     */
    internal fun sinceLastAnswer(): Duration = elapsed() - lastAnswer.get().nanoseconds

    internal fun outstanding(window: Duration): Outstanding = pending.close(elapsed(), window)

    /** [count] answers beyond the ones a step has already waited for, or why they did not arrive. */
    internal fun awaitMatched(count: Long, within: Duration): Reason? {
        val target = awaited.get() + count
        try {
            waitFor(target, System.nanoTime() + within.inWholeNanoseconds)
        } catch (interrupted: InterruptedException) {
            // A run being torn down under a user, not a target that failed to
            // answer, so the flag goes back for whoever is doing the tearing.
            Thread.currentThread().interrupt()
            return Threw(interrupted.javaClass.simpleName)
        }
        if (paired.get() < target) return if (ended.count == 0L) StreamEnded else TimedOut
        awaited.set(target)
        return null
    }

    override fun onNext(answer: A) {
        val at = elapsed()
        val id = waiting.poll()
        if (id == null) {
            unasked.incrementAndGet()
            return
        }
        pending.observed(id, at)?.let(answered::add)
        lastAnswer.set(at.inWholeNanoseconds)
        paired.incrementAndGet()
        signal()
    }

    override fun onError(error: Throwable) {
        ended.countDown()
        signal()
    }

    override fun onCompleted() {
        ended.countDown()
        signal()
    }

    private fun signal() = gate.withLock { arrived.signalAll() }

    private fun waitFor(target: Long, deadline: Long) = gate.withLock {
        while (paired.get() < target && ended.count > 0L) {
            if (!arrived.await(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) return@withLock
        }
    }

    private fun elapsed(): Duration = (System.nanoTime() - origin).nanoseconds
}

private fun Status.asStreamReason(): Reason =
    if (code == Status.Code.DEADLINE_EXCEEDED) TimedOut else GrpcStatus(code)
