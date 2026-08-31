package io.github.matthewjones372.kestrel.websocket

import io.github.matthewjones372.kestrel.Action
import io.github.matthewjones372.kestrel.Correlation
import io.github.matthewjones372.kestrel.Outstanding
import io.github.matthewjones372.kestrel.Pending
import io.github.matthewjones372.kestrel.ScenarioBuilder
import io.github.matthewjones372.kestrel.Session
import io.github.matthewjones372.kestrel.StepName
import io.github.matthewjones372.kestrel.StepScope
import io.github.matthewjones372.kestrel.action
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    exec(name, Action { session -> action { sendOn(session, frame, keyedBy) }.run(session) })
}

private fun StepScope.sendOn(session: Session, frame: WsFrame, keyedBy: Correlation) {
    val open = this[connection] ?: return fail(NotConnected)
    val id = keyedBy.of(session)
    // Registered before the write rather than after it: a quick target can
    // answer while the write's own future is still completing, and an answer
    // that finds nothing outstanding is counted as a message nobody asked for.
    open.inbound.departed(id)
    if (frame.writtenTo(open.socket).settled(this, writeTimeout) == null) open.inbound.aborted(id)
}

/** The JDK's two writes, chosen by which frame this is. */
private fun WsFrame.writtenTo(socket: WebSocket): CompletableFuture<WebSocket> = when (this) {
    is WsFrame.Text -> socket.sendText(text, true)

    // A buffer of its own per send: the client reads the position as it writes,
    // so one shared between two users would be drained twice.
    is WsFrame.Binary -> socket.sendBinary(ByteBuffer.wrap(bytes), true)
}

/**
 * The receiving side of one connection: the far end's Close, and the sends that
 * are still owed an answer.
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

    fun outstanding(window: Duration): Outstanding = pending.close(elapsed(), window)

    fun awaitClosed(within: Duration): Boolean = closed.await(within.inWholeMilliseconds, TimeUnit.MILLISECONDS)

    override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
        closed.countDown()
        return null
    }

    override fun onError(webSocket: WebSocket, error: Throwable) {
        closed.countDown()
    }

    private fun elapsed(): Duration = (System.nanoTime() - origin).nanoseconds
}
