package io.github.matthewjones372.kestrel.websocket

import io.github.matthewjones372.kestrel.Action
import io.github.matthewjones372.kestrel.Correlation
import io.github.matthewjones372.kestrel.Outstanding
import io.github.matthewjones372.kestrel.Pending
import io.github.matthewjones372.kestrel.Reason
import io.github.matthewjones372.kestrel.ScenarioBuilder
import io.github.matthewjones372.kestrel.StepName
import io.github.matthewjones372.kestrel.StepScope
import io.github.matthewjones372.kestrel.Threw
import io.github.matthewjones372.kestrel.TimedOut
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/** How long a write is given before it is a failure rather than a slow success. */
internal val writeTimeout: Duration = 30.seconds

/**
 * One message out, as a departure that does not wait.
 *
 * The sample is the write alone: it starts when the frame is handed to the
 * client and ends when the client reports it written. No answer is waited for
 * here — an answer arrives later on the same socket, and `awaiting` is where it
 * is matched to the send [keyedBy] named.
 */
fun ScenarioBuilder.send(name: StepName, frame: WsFrame, keyedBy: Correlation) {
    // The correlation reads the whole session, which a step scope does not hand
    // out, so the body runs inside an action that has both.
    exec(name, Action { scope -> scope.sendOn(frame, keyedBy) })
}

private fun StepScope.sendOn(frame: WsFrame, keyedBy: Correlation) {
    val open = this[connection] ?: return fail(NotConnected)
    val id = keyedBy.of(session)
    // Registered before the write rather than after it: a quick target can
    // answer while the write's own future is still completing, and an answer
    // that finds nothing outstanding is counted as a message nobody asked for.
    open.inbound.departed(id)
    if (frame.writtenTo(open.socket).settled(this, writeTimeout) == null) open.inbound.aborted(id)
}

/**
 * The far end let go, or the socket broke, before the answers a step was
 * waiting for arrived. A finding rather than something to paper over: nothing
 * here reconnects.
 */
data object Disconnected : Reason {
    override val described: String get() = "disconnected"
}

/**
 * [count] answers to sends this connection has already made.
 *
 * One sample per answer, each measured from the send it answers rather than
 * from the step: `awaiting(count = 100)` is a hundred samples under this name,
 * so the distribution of the messages is what the report draws. The users that
 * reached the step are still counted once.
 *
 * The samples are placed on the timeline at the moment the wait finished
 * rather than at each message's own second — the client's reader thread counts
 * from its own connection, not from the run — so a long wait reports its
 * durations exactly and their placement coarsely.
 *
 * An answer is paired with the send at the head of the queue, which on one
 * socket is the order they left in. A message arriving with nothing outstanding
 * answers no send: it is counted as unsolicited, and not timed, because there
 * is no departure to measure it from.
 */
fun ScenarioBuilder.awaiting(name: StepName, count: Int, within: Duration) {
    require(count > 0) { "a step cannot wait for $count messages" }
    exec(name) { awaitOn(count.toLong(), within) }
}

private fun StepScope.awaitOn(count: Long, within: Duration) {
    val open = this[connection] ?: return fail(NotConnected)
    // The wait is bounded and happens on the user's own virtual thread, which
    // unmounts while it blocks: a target that goes quiet costs a carrier
    // nothing, and is a failure rather than a user parked for the rest of the
    // run.
    val why = open.inbound.awaitMatched(count, within)
    // Reported whether the wait succeeded or not: the answers that did arrive
    // were measured, and a step that timed out after ninety of a hundred has
    // ninety real latencies to show beside the failure.
    open.inbound.takeAnswered().forEach { sample(it) }
    why?.let { fail(it) }
}

/** The JDK's two writes, chosen by which frame this is. */
private fun WsFrame.writtenTo(socket: WebSocket): CompletableFuture<WebSocket> = when (this) {
    is WsFrame.Text -> socket.sendText(text, true)

    // A buffer of its own per send: the client reads the position as it writes,
    // so one shared between two users would be drained twice.
    is WsFrame.Binary -> socket.sendBinary(ByteBuffer.wrap(bytes), true)
}

/**
 * The receiving side of one connection: the far end's Close, the messages that
 * arrive, and the sends that are still owed one.
 *
 * Shared between the user that sends and the client's thread that reads the
 * socket, so it is the one structure here that is thread-safe.
 */
internal class Inbound : WebSocket.Listener {

    private val origin = System.nanoTime()

    private val closed = CountDownLatch(1)

    private val pending = Pending()

    // Which send an answer belongs to is the head of this queue rather than
    // anything read out of the message: answers arrive on one socket in the
    // order the sends left it, and this module parses no payloads.
    private val waiting = ConcurrentLinkedQueue<Long>()

    private val paired = AtomicLong()

    // Each answer's own latency, measured from the send it answers, kept until
    // the waiting step reports it. Computed here already — `received` had been
    // throwing the result away — and drained on the user's own thread, so the
    // client's reader thread never touches a recorder.
    private val answered = ConcurrentLinkedQueue<Duration>()

    // What a step has already waited for, touched by that user's thread alone:
    // two `awaiting` steps in one scenario wait for their own answers rather
    // than both being satisfied by the first one's.
    private val awaited = AtomicLong()

    private val unasked = AtomicLong()

    private val gate = ReentrantLock()

    private val arrived = gate.newCondition()

    val matched: Long get() = paired.get()

    val unsolicited: Long get() = unasked.get()

    /** A send that left, keyed by the correlation it carried. */
    fun departed(id: Long) {
        val at = elapsed()
        pending.departed(id, intended = at, at = at)
        waiting.add(id)
    }

    /**
     * A send that never left. Its departure is claimed back, so a write that
     * failed is a failed step rather than that and an answer nobody sent.
     */
    fun aborted(id: Long) {
        waiting.remove(id)
        pending.observed(id, elapsed())
    }

    /**
     * The latencies of the answers that have arrived and not yet been
     * reported, oldest first, taken out as they are read.
     */
    fun takeAnswered(): List<Duration> = generateSequence { answered.poll() }.toList()

    fun outstanding(window: Duration): Outstanding = pending.close(elapsed(), window)

    fun awaitClosed(within: Duration): Boolean = closed.await(within.inWholeMilliseconds, TimeUnit.MILLISECONDS)

    /** [count] answers beyond the ones a step has already waited for, or why they did not arrive. */
    fun awaitMatched(count: Long, within: Duration): Reason? {
        val target = awaited.get() + count
        try {
            waitFor(target, System.nanoTime() + within.inWholeNanoseconds)
        } catch (interrupted: InterruptedException) {
            // A run being torn down under a user, not a target that failed to
            // answer, so the flag goes back for whoever is doing the tearing.
            Thread.currentThread().interrupt()
            return Threw(interrupted.javaClass.simpleName)
        }
        if (paired.get() < target) return if (closed.count == 0L) Disconnected else TimedOut
        awaited.set(target)
        return null
    }

    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
        // A message is one message however many frames carried it, and it has
        // arrived when its last one has.
        if (last) received()
        // The default listener asks for the next message; an override has to.
        webSocket.request(1)
        return null
    }

    override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
        if (last) received()
        webSocket.request(1)
        return null
    }

    override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
        dropped()
        return null
    }

    override fun onError(webSocket: WebSocket, error: Throwable) {
        dropped()
    }

    private fun received() {
        val at = elapsed()
        val id = waiting.poll()
        if (id == null) {
            unasked.incrementAndGet()
            return
        }
        pending.observed(id, at)?.let(answered::add)
        paired.incrementAndGet()
        signal()
    }

    private fun dropped() {
        closed.countDown()
        signal()
    }

    private fun signal() = gate.withLock { arrived.signalAll() }

    private fun waitFor(target: Long, deadline: Long) = gate.withLock {
        while (paired.get() < target && closed.count > 0L) {
            if (!arrived.await(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) return@withLock
        }
    }

    private fun elapsed(): Duration = (System.nanoTime() - origin).nanoseconds
}
