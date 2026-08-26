package io.github.matthewjones372.kestrel.pelican

import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.kestrel.RunRecorder
import io.github.matthewjones372.pelican.ClientRequest
import io.github.matthewjones372.pelican.Method
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Instant

class TransportTest {

    private lateinit var server: HttpServer
    private val started = Instant.parse("2026-08-26T09:00:00Z")

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            val status = if (exchange.requestURI.path.endsWith("/missing")) 404 else 200
            val body = """{"id":1}""".toByteArray()
            exchange.responseHeaders.add("x-echo", exchange.requestMethod)
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun url(path: String) = "http://localhost:${server.address.port}$path"

    @Test
    fun `a request goes out and its response comes back`() {
        val transport = kestrelTransport()

        val response = transport.send(ClientRequest(Method.GET, url("/orders/1"))).toCompletableFuture().join()

        response.status shouldBe 200
        response.header("x-echo") shouldBe "GET"
        response.text() shouldBe """{"id":1}"""
    }

    @Test
    fun `a body and its headers reach the server`() {
        val transport = kestrelTransport()

        val response = transport.send(
            ClientRequest(
                method = Method.POST,
                url = url("/orders"),
                headers = listOf("content-type" to "application/json"),
                body = ClientRequest.Body.Text("""{"cart":"1 anvil"}"""),
            ),
        ).toCompletableFuture().join()

        response.header("x-echo") shouldBe "POST"
    }

    @Test
    fun `every exchange is recorded under the template, so ten ids are one row`() {
        val recorder = RunRecorder(started)
        val transport = kestrelTransport(recorder = recorder, templates = listOf("/orders/{id}"))

        repeat(10) { id -> transport.send(ClientRequest(Method.GET, url("/orders/$id"))).toCompletableFuture().join() }

        val result = recorder.freeze()
        result.steps.keys shouldContainExactly setOf("GET /orders/{id}")
        result["GET /orders/{id}"].count shouldBe 10L
    }

    @Test
    fun `a status the endpoint did not describe is the failure reason`() {
        val recorder = RunRecorder(started)
        val transport = kestrelTransport(recorder = recorder, templates = listOf("/orders/{id}/missing"))

        transport.send(ClientRequest(Method.GET, url("/orders/7/missing"))).toCompletableFuture().join()

        recorder.freeze()["GET /orders/{id}/missing"].failures shouldBe mapOf("status 404" to 1L)
    }

    @Test
    fun `a url no template matches is still one row, not one row per id`() {
        val recorder = RunRecorder(started)
        val transport = kestrelTransport(recorder = recorder)

        transport.send(ClientRequest(Method.GET, url("/carts/42"))).toCompletableFuture().join()
        transport.send(ClientRequest(Method.GET, url("/carts/99"))).toCompletableFuture().join()

        recorder.freeze().steps.keys shouldContainExactly setOf("GET /carts/{}")
    }

    @Test
    fun `a transport that reaches nothing records the failure rather than throwing`() {
        val recorder = RunRecorder(started)
        val transport = kestrelTransport(recorder = recorder, templates = listOf("/orders"))

        val thrown = runCatching {
            transport.send(ClientRequest(Method.GET, "http://localhost:1/orders")).toCompletableFuture().join()
        }

        thrown.isFailure shouldBe true
        recorder.freeze()["GET /orders"].failed shouldBe 1L
    }
}
