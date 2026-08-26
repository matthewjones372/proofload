package io.github.matthewjones372.kestrel.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

/** What the server was asked for, so a test can assert the wire and not the intent. */
internal data class Received(
    val method: String,
    val path: String,
    val body: String,
    val headers: Map<String, String>,
)

internal data class Reply(
    val status: Int,
    val body: String = "",
    val headers: Map<String, String> = emptyMap(),
    val delayMillis: Long = 0,
)

internal class TestServer(private val server: HttpServer, val received: List<Received>) {

    val baseUrl: String get() = "http://localhost:${server.address.port}"
}

/** A URL with nothing listening on it: a port taken and released. */
internal fun closedPortUrl(): String = ServerSocket(0).use { "http://localhost:${it.localPort}" }

/**
 * Runs [block] against a JDK `HttpServer` on an ephemeral port. Port 0 rather
 * than a fixed one so parallel test JVMs do not collide.
 */
internal fun serving(vararg routes: Pair<String, Reply>, block: (TestServer) -> Unit) {
    val server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
    val received = CopyOnWriteArrayList<Received>()
    val table = routes.toMap()
    server.createContext("/") { exchange ->
        received += exchange.record()
        exchange.reply(table[exchange.requestURI.path] ?: Reply(HTTP_NOT_FOUND))
    }
    server.start()
    try {
        block(TestServer(server, received))
    } finally {
        server.stop(0)
    }
}

private const val HTTP_NOT_FOUND = 404

private fun HttpExchange.record(): Received = Received(
    method = requestMethod,
    path = requestURI.path,
    body = requestBody.readBytes().decodeToString(),
    headers = requestHeaders.mapValues { (_, values) -> values.first() }.mapKeys { (name, _) -> name.lowercase() },
)

private fun HttpExchange.reply(reply: Reply) {
    if (reply.delayMillis > 0) Thread.sleep(reply.delayMillis)
    reply.headers.forEach { (name, value) -> responseHeaders.add(name, value) }
    val bytes = reply.body.encodeToByteArray()
    // -1, not 0: zero means "chunked, length unknown" to HttpServer, and a
    // client then waits for a body that never comes.
    sendResponseHeaders(reply.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}
