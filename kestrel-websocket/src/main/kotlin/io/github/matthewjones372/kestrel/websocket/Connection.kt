package io.github.matthewjones372.kestrel.websocket

import io.github.matthewjones372.kestrel.Outstanding
import io.github.matthewjones372.kestrel.Reason
import io.github.matthewjones372.kestrel.ScenarioBuilder
import io.github.matthewjones372.kestrel.SessionKey
import io.github.matthewjones372.kestrel.StepName
import io.github.matthewjones372.kestrel.StepScope
import io.github.matthewjones372.kestrel.Threw
import io.github.matthewjones372.kestrel.TimedOut
import io.github.matthewjones372.kestrel.sessionKey
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** How long either handshake is given before it is a failure rather than a slow success. */
internal val handshakeTimeout: Duration = 30.seconds

/**
 * A step that needed a connection nothing had opened: a `close` with no `open`
 * before it, or one whose `open` failed and left the user without a socket.
 */
data object NotConnected : Reason {
    override val described: String get() = "not connected"
}

/**
 * One user's socket.
 *
 * Per user rather than pooled, which is the opposite of `kestrel-http`'s shared
 * client and for the opposite reason: a stream test is about how many
 * connections a target holds, so amortising the handshake would remove what is
 * being measured. File descriptors then bound the user count long before the
 * scheduler does.
 */
class Connection internal constructor(
    internal val socket: WebSocket,
    internal val inbound: Inbound,
) {

    /**
     * Sends this connection has still to see an answer to, split by whether
     * they were given [window] to be answered in — a send that left too late to
     * have had it is still moving rather than lost.
     */
    fun outstanding(window: Duration): Outstanding = inbound.outstanding(window)

    /** Whether the far end answered the Close inside [within]. */
    internal fun awaitClosed(within: Duration): Boolean = inbound.awaitClosed(within)
}

/** Where [open] leaves the connection and [close] goes looking for it. */
val connection: SessionKey<Connection> = sessionKey("kestrel.websocket.connection")

/**
 * The opening handshake as one timed step.
 *
 * The sample is the upgrade alone: it starts when the request leaves and ends
 * when the server's 101 completes it. It is not a first message and not a first
 * byte of one — no message has been asked for at this point, and nothing here
 * says when data starts to flow.
 */
fun ScenarioBuilder.open(name: StepName, target: WsTarget) {
    exec(name) { openTo(target) }
}

/**
 * The closing handshake as one timed step.
 *
 * The sample starts when the Close frame is written and ends when the far end's
 * Close arrives back, so it is what the target took to let go rather than what
 * the write took. The connection is closed by the time this step ends.
 */
fun ScenarioBuilder.close(name: StepName) {
    exec(name) { closeOpen() }
}

/**
 * One client for the whole run, and one connection per user regardless: an
 * upgrade takes a connection of its own that the client never pools, so sharing
 * the client amortises nothing that is being measured, while a client per user
 * would build a selector and an executor inside the handshake's sample.
 */
private val handshakes: HttpClient by lazy { HttpClient.newHttpClient() }

private fun StepScope.openTo(target: WsTarget) {
    val inbound = Inbound()
    val socket = handshakes.newWebSocketBuilder()
        .connectTimeout(handshakeTimeout.toJavaDuration())
        .buildAsync(URI.create(target.url), inbound)
        .settled(this, handshakeTimeout) ?: return
    set(connection, Connection(socket, inbound))
}

private fun StepScope.closeOpen() {
    val open = this[connection] ?: return fail(NotConnected)
    open.socket.sendClose(WebSocket.NORMAL_CLOSURE, "").settled(this, handshakeTimeout) ?: return
    // Waiting here is the measurement, not a stall: the step is timed for the
    // round trip, and the gate is bounded so a peer that never answers is a
    // failure rather than a user parked for the rest of the run.
    if (!open.awaitClosed(handshakeTimeout)) fail(TimedOut)
}

/**
 * Waits for [this] up to [within], turning the client's failures into a failure
 * on [scope] and returning null. Nothing throws out of here: an engine reads the
 * step's result to measure a failure, so a target's bad day must not arrive by
 * the same route as a bug in the generator.
 */
internal fun <T> CompletableFuture<T>.settled(scope: StepScope, within: Duration): T? =
    try {
        orTimeout(within.inWholeMilliseconds, TimeUnit.MILLISECONDS).join()
    } catch (failure: CompletionException) {
        scope.fail(failure.reason())
        null
    }

/**
 * The class name, not the message: `ConnectException` is one row in a report,
 * while its message carries a host and a port and would be thousands. A timeout
 * is named instead of classed because it is the one failure a reader acts on
 * differently — the target was reachable and did not answer in time.
 */
private fun CompletionException.reason(): Reason {
    val failure = cause ?: this
    if (failure is TimeoutException) return TimedOut
    return Threw(failure::class.simpleName ?: failure.javaClass.name)
}
