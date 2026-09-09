package io.github.matthewjones372.proofload.http

import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.proofload.Session
import io.github.matthewjones372.proofload.StepResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

/**
 * A hop is another round trip under the same step's name. One request, with
 * the trips behind it counted, rather than one request that quietly took two.
 */
class AttemptsTest {

    private lateinit var server: HttpServer

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/in") { exchange ->
            exchange.responseHeaders.add("location", "/home")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/home") { exchange ->
            val body = "home".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun api() = http.baseUrl("http://localhost:${server.address.port}")

    @Test
    fun `a request that goes straight there is one request and one attempt`() {
        val result = api().get("/home").run(Session.empty)

        result.shouldBeInstanceOf<StepResult.Ok>().attempts shouldBe 1
    }

    @Test
    fun `a one-hop redirect is one request and two attempts`() {
        val result = api().get("/in").following().run(Session.empty)

        result.shouldBeInstanceOf<StepResult.Ok>().attempts shouldBe 2
    }
}
