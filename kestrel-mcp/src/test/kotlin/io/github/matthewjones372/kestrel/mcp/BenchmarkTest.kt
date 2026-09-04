package io.github.matthewjones372.kestrel.mcp

import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.kestrel.Allowance
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * "Benchmark my app and report the numbers" is one sentence, and was seven
 * calls. This is the one it should have been.
 */
class BenchmarkTest {

    private lateinit var server: HttpServer
    private val arrived = AtomicInteger()

    @BeforeEach
    fun start() {
        arrived.set(0)
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            arrived.incrementAndGet()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.createContext("/broken") { exchange ->
            arrived.incrementAndGet()
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        server.createContext("/orders") { exchange ->
            arrived.incrementAndGet()
            val body = "[]".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private val where get() = "http://localhost:${server.address.port}"

    private fun document(): String = """
        openapi: 3.1.0
        servers: [{url: "$where"}]
        paths:
          /orders:
            get:
              operationId: listOrders
              responses:
                "200": {description: ok}
    """.trimIndent()

    @Test
    fun `a document becomes a plan, a preview and a smoke, in one call`() {
        val answered = benchmark(mapOf("document" to document()), Allowance.none)

        withClue(answered) {
            answered shouldContain "kestrel:  plan/1"
            answered shouldContain "listOrders"
            answered shouldContain "Running this would send"
            answered shouldContain "One request per step"
        }
    }

    @Test
    fun `it sends the smoke and no load`() {
        benchmark(mapOf("document" to document()), Allowance.none)

        withClue("one step, one request; the plan it wrote asks for ten") {
            arrived.get() shouldBe 1
        }
    }

    @Test
    fun `it says the run has not happened`() {
        withClue("a caller that thinks this ran the load will never call run") {
            benchmark(mapOf("document" to document()), Allowance.none) shouldContain "Nothing above sent load"
        }
    }

    @Test
    fun `a bare url is enough to ask whether anything is there`() {
        val answered = benchmark(mapOf("baseUrl" to where), Allowance.none)

        withClue(answered) {
            answered shouldContain "get: /"
            answered shouldContain "1 requests, 1 ok"
        }
    }

    @Test
    fun `a plan somebody already has is not regenerated`() {
        val mine = """
            kestrel:  plan/1
            baseUrl:  $where
            scenario: mine
            steps:
              - name: orders
                get:  /orders
            load:
              rate: 5/s
              over: 1s
        """.trimIndent()

        benchmark(mapOf("plan" to mine), Allowance.none) shouldContain "scenario: mine"
    }

    @Test
    fun `a host the machine does not allow is refused, with the plan still shown`() {
        val answered = benchmark(mapOf("document" to document()), Allowance(hosts = listOf("example.invalid")))

        withClue(answered) {
            answered shouldContain """"isError":true"""
            answered shouldContain "refused"
            withClue("a caller told only that a host is barred cannot see what it was about to send there") {
                answered shouldContain "listOrders"
            }
        }
        arrived.get() shouldBe 0
    }

    @Test
    fun `a smoke that failed is the answer, not a footnote`() {
        // Not an unmapped path: `/` is a catch-all context in HttpServer, so an
        // unknown path is served 200 by it and proves nothing.
        val missing = document().replace("/orders", "/broken")

        withClue("a plan whose steps 404 once will 404 three thousand times") {
            benchmark(mapOf("document" to missing), Allowance.none) shouldContain """"isError":true"""
        }
    }

    @Test
    fun `nothing to benchmark says what it wanted`() {
        benchmark(emptyMap(), Allowance.none) shouldContain "wants one of"
    }
}
