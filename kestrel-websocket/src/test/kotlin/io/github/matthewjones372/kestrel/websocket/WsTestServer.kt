package io.github.matthewjones372.kestrel.websocket

import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** The constant RFC 6455 has a server mix into the key it echoes back. */
private const val ACCEPT_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

internal const val TEXT_OPCODE = 0x1

internal const val BINARY_OPCODE = 0x2

private const val CLOSE_OPCODE = 0x8

private const val SHUTDOWN_MILLIS = 2_000L

/** Normal closure, 1000, unmasked the way a server's frames are. */
private val CLOSE_FRAME = byteArrayOf(0x88.toByte(), 0x02, 0x03, 0xE8.toByte())

/**
 * A socket that answers one upgrade and then does what it was asked for at
 * [accepting]: pushes messages nobody sent for, answers the ones it is sent,
 * or hangs up.
 */
internal class WsTestServer(
    private val server: ServerSocket,
    private val pushing: Int,
    private val answering: Boolean,
    private val hangingUp: Boolean,
) {

    private val closing = CountDownLatch(1)

    private val read = LinkedBlockingQueue<Int>()

    val url: String get() = "ws://localhost:${server.localPort}"

    /** Whether the client's Close frame reached the server inside [millis]. */
    fun sawClose(millis: Long): Boolean = closing.await(millis, TimeUnit.MILLISECONDS)

    /** The opcodes of the next [count] messages, waiting up to [millis] for each, null where none came. */
    fun sawMessages(count: Int, millis: Long): List<Int?> = List(count) { read.poll(millis, TimeUnit.MILLISECONDS) }

    fun serve(client: Socket) {
        client.write(switchingProtocols(client.getInputStream().readHeaders()).toByteArray())
        repeat(pushing) { client.writeText("push") }
        if (hangingUp) return client.write(CLOSE_FRAME)
        while (true) {
            val opcode = client.getInputStream().readFrame() ?: return
            if (opcode == CLOSE_OPCODE) {
                closing.countDown()
                // The Close the client's listener is waiting for.
                return client.write(CLOSE_FRAME)
            }
            read.put(opcode)
            if (answering) client.writeText("answer")
        }
    }
}

/**
 * Runs [block] against a server socket, torn down afterwards however the test
 * left it.
 *
 * `com.sun.net.httpserver.HttpServer` cannot stand in here: it owns the
 * connection and has no way to hand the raw stream over after a 101, so a
 * handshake it answers leaves no socket to speak WebSocket on.
 */
internal fun accepting(
    pushing: Int = 0,
    answering: Boolean = false,
    hangingUp: Boolean = false,
    block: (WsTestServer) -> Unit,
) {
    val socket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    val server = WsTestServer(socket, pushing, answering, hangingUp)
    val accepted = AtomicReference<Socket>()
    val worker = thread(isDaemon = true) {
        try {
            socket.accept().also(accepted::set).use(server::serve)
        } catch (_: IOException) {
            // Either socket closing under this thread is how the test says it
            // is finished, and both arrive here.
        }
    }
    try {
        block(server)
    } finally {
        socket.close()
        // A test that never closed its connection leaves this thread waiting
        // for a frame that is not coming.
        accepted.get()?.close()
        worker.join(SHUTDOWN_MILLIS)
    }
}

private fun Socket.write(bytes: ByteArray) {
    getOutputStream().write(bytes)
    getOutputStream().flush()
}

/** A server's frames are unmasked, and every payload here is short enough to carry its length in one byte. */
private fun Socket.writeText(text: String) {
    val payload = text.toByteArray()
    write(byteArrayOf(0x81.toByte(), payload.size.toByte()) + payload)
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

/**
 * The opcode of the next frame, its payload read and discarded: this server
 * counts what it is sent rather than reading it. Null at the end of the stream.
 */
private fun InputStream.readFrame(): Int? {
    val first = read()
    val second = read()
    if (first < 0 || second < 0) return null
    // Every payload a test sends is short, so the seven-bit length is the whole
    // length and neither long form can arrive. A client's frames are masked,
    // and the key sits between the length and the payload.
    if ((second and 0x80) != 0) readNBytes(4)
    readNBytes(second and 0x7F)
    return first and 0x0F
}
