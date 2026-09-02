package io.github.matthewjones372.kestrel.http

import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.kestrel.Session
import io.github.matthewjones372.kestrel.StepScope
import io.github.matthewjones372.kestrel.sessionKey
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

/**
 * A trace says what was actually sent. A step that reports only its name and
 * outcome leaves a reader guessing which id went into the path.
 */
class NarratingTest {

    private lateinit var server: HttpServer

    private val orderId = sessionKey<String>("orderId")

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            val body = """{"id":"9f3"}""".toByteArray()
            exchange.responseHeaders.add("location", "/orders/9f3")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun api() = http.baseUrl("http://localhost:${server.address.port}")

    private fun narrated(action: HttpAction, session: Session = Session.empty): List<String> {
        val notes = mutableListOf<String>()
        StepScope(session, notes = notes).also(action::run)
        return notes
    }

    @Test
    fun `a templated path is narrated filled in, not as the template`() {
        val notes = narrated(api().get("/orders/{orderId}"), Session.empty.set(orderId, "9f3"))

        notes.first() shouldContain "/orders/9f3"
    }

    @Test
    fun `the headers and body it sent are narrated`() {
        val notes = narrated(api().post("/orders").header("content-type", "application/json").body("""{"a":1}"""))

        notes shouldContain "> content-type: application/json"
        notes shouldContain """> {"a":1}"""
    }

    @Test
    fun `the status and body it got back are narrated`() {
        val notes = narrated(api().get("/orders"))

        notes shouldContain "< 200"
        notes shouldContain """< {"id":"9f3"}"""
    }

    @Test
    fun `a capture is narrated with the value it took`() {
        val notes = narrated(api().get("/orders").capture(orderId) { it.header("location") })

        notes shouldContain "captured orderId = /orders/9f3"
    }

    @Test
    fun `a capture that found nothing says what it was reading`() {
        val notes = narrated(api().get("/orders").capture(orderId) { it.header("x-nothing") })

        notes shouldContain "captured nothing for orderId"
    }

    @Test
    fun `a run that is not narrating builds no lines at all`() {
        val notes = mutableListOf<String>()
        StepScope(Session.empty).also(api().get("/orders")::run)

        notes.shouldBeEmpty()
    }
}
