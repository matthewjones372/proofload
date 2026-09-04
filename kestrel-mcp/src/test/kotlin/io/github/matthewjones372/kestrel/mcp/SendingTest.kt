package io.github.matthewjones372.kestrel.mcp

import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.kestrel.Allowance
import io.github.matthewjones372.kestrel.perSecond
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * The debug loop. A plan answering 400s produces a run full of them and the run
 * says only that they were 400s; these are how a caller finds out why without
 * sending load to do it.
 */
class SendingTest {

    private lateinit var server: HttpServer
    private val arrived = AtomicInteger()

    @BeforeEach
    fun start() {
        arrived.set(0)
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/products") { exchange ->
            arrived.incrementAndGet()
            val body = "[]".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/missing") { exchange ->
            arrived.incrementAndGet()
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun plan(second: String = "/products"): String = """
        kestrel:  plan/1
        baseUrl:  http://localhost:${server.address.port}
        scenario: checkout
        steps:
          - name: browse
            get:  /products
          - name: second
            get:  $second
        load:
          rate: 5000/s
          over: 10m
    """.trimIndent()

    @Test
    fun `smoke sends one request per step, not what the rate asked for`() {
        val answered = smoke(mapOf("plan" to plan()), Allowance.none)

        withClue(answered) {
            answered shouldContain "2 requests"
        }
        withClue("the plan says five thousand a second for ten minutes; a smoke is bounded by its shape") {
            arrived.get() shouldBeLessThanOrEqual 2
        }
    }

    @Test
    fun `smoke says which step failed and why`() {
        val answered = smoke(mapOf("plan" to plan(second = "/missing")), Allowance.none)

        withClue(answered) {
            answered shouldContain """"isError":true"""
            answered shouldContain "second"
            answered shouldContain "status 404"
        }
    }

    @Test
    fun `trace says what was actually sent`() {
        val answered = trace(mapOf("plan" to plan()), Allowance.none)

        withClue(answered) {
            answered shouldContain "browse"
            answered shouldContain "GET"
            answered shouldContain "/products"
        }
    }

    @Test
    fun `neither writes to the stream the protocol uses`() {
        val caught = java.io.ByteArrayOutputStream()
        val was = System.out
        System.setOut(java.io.PrintStream(caught, true, Charsets.UTF_8))
        try {
            smoke(mapOf("plan" to plan()), Allowance.none)
            trace(mapOf("plan" to plan()), Allowance.none)
        } finally {
            System.setOut(was)
        }

        withClue("a library print landing mid-message ends the session: ${caught.toString(Charsets.UTF_8)}") {
            caught.toString(Charsets.UTF_8) shouldBe ""
        }
    }

    @Test
    fun `a host the machine does not permit is refused, and nothing is sent`() {
        val answered = smoke(mapOf("plan" to plan()), Allowance(hosts = listOf("example.invalid")))

        withClue(answered) {
            answered shouldContain """"isError":true"""
            answered shouldContain "refused"
        }
        arrived.get() shouldBe 0
    }

    @Test
    fun `a rate the machine would refuse does not refuse a smoke`() {
        val answered = smoke(mapOf("plan" to plan()), Allowance(maxRate = 1.perSecond))

        withClue("one request per step is inside any ceiling; the host is the part that still matters") {
            answered shouldContain """"isError":false"""
        }
    }
}
