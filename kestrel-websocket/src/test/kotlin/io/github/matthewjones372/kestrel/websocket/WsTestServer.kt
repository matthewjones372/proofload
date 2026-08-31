package io.github.matthewjones372.kestrel.websocket

import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** The constant RFC 6455 has a server mix into the key it echoes back. */
private const val ACCEPT_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

private const val CLOSE_OPCODE = 0x8

private const val SHUTDOWN_MILLIS = 2_000L

internal class WsTestServer(private val server: ServerSocket, private val closing: CountDownLatch) {

    val url: String get() = "ws://localhost:${server.localPort}"

    /** Whether the client's Close frame reached the server inside [millis]. */
    fun sawClose(millis: Long): Boolean = closing.await(millis, TimeUnit.MILLISECONDS)
}

/**
 * Runs [block] against a socket that answers one upgrade and replies to the
 * Close it is sent.
 *
 * `com.sun.net.httpserver.HttpServer` cannot stand in here: it owns the
 * connection and has no way to hand the raw stream over after a 101, so a
 * handshake it answers leaves no socket to speak WebSocket on.
 */
internal fun accepting(block: (WsTestServer) -> Unit) {
    val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    val closing = CountDownLatch(1)
    val accepted = AtomicReference<Socket>()
    val worker = thread(isDaemon = true) {
        try {
            server.accept().also(accepted::set).use { it.upgradeAndClose(closing) }
        } catch (_: IOException) {
            // Either socket closing under this thread is how the test says it
            // is finished, and both arrive here.
        }
    }
    try {
        block(WsTestServer(server, closing))
    } finally {
        server.close()
        // A test that never closed its connection leaves this thread waiting
        // for a Close frame that is not coming.
        accepted.get()?.close()
        worker.join(SHUTDOWN_MILLIS)
    }
}

private fun Socket.upgradeAndClose(closing: CountDownLatch) {
    val request = getInputStream().readHeaders()
    getOutputStream().write(switchingProtocols(request).toByteArray())
    getOutputStream().flush()
    if (getInputStream().readsClose()) closing.countDown()
    // The Close the client's listener is waiting for: normal closure, 1000.
    getOutputStream().write(byteArrayOf(0x88.toByte(), 0x02, 0x03, 0xE8.toByte()))
    getOutputStream().flush()
}

private fun InputStream.readHeaders(): String = buildString {
    while (!endsWith("\r\n\r\n")) {
        val byte = read()
        if (byte < 0) return@buildString
        append(byte.toChar())
    }
}

private fun switchingProtocols(request: String): String {
    val key = request.lineSequence()
        .first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
        .substringAfter(':')
        .trim()
    val accept = MessageDigest.getInstance("SHA-1").digest((key + ACCEPT_GUID).toByteArray())
    return "HTTP/1.1 101 Switching Protocols\r\n" +
        "Upgrade: websocket\r\n" +
        "Connection: Upgrade\r\n" +
        "Sec-WebSocket-Accept: ${Base64.getEncoder().encodeToString(accept)}\r\n" +
        "\r\n"
}

/** The opcode is the low nibble of a frame's first byte, which is all this needs to read. */
private fun InputStream.readsClose(): Boolean = read().let { it >= 0 && (it and 0x0F) == CLOSE_OPCODE }
