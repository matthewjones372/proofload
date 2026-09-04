package io.github.matthewjones372.kestrel.mcp

import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.kestrel.Allowance
import io.github.matthewjones372.kestrel.perSecond
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * These three are the ones a caller may call as often as it likes. The claim
 * worth pinning is that none of them reaches the target: a counting server says
 * so, rather than the tools being trusted about themselves.
 */
class ReadingTest {

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
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun plan(): String = """
        kestrel:  plan/1
        baseUrl:  http://localhost:${server.address.port}
        scenario: smoke
        steps:
          - name: root
            get:  /
        load:
          rate: 50/s
          over: 1m
    """.trimIndent()

    @Test
    fun `not one of them opens a socket`() {
        validate(mapOf("plan" to plan()))
        preview(mapOf("plan" to plan()), Allowance.none)
        fromOpenApi(mapOf("document" to DOCUMENT))

        withClue("a tool a caller is told is free must be free") {
            arrived.get() shouldBe 0
        }
    }

    @Test
    fun `validate names the line of what is wrong`() {
        val answered = validate(mapOf("plan" to plan().replace("scenario:", "scenarios:")))

        withClue(answered) {
            answered shouldContain """"isError":true"""
            answered shouldContain "line 3"
            answered shouldContain "scenarios"
        }
    }

    @Test
    fun `validate refuses a goal naming a step nobody declared`() {
        val answered = validate(mapOf("plan" to plan() + "\ngoals:\n  - step: pay\n    p99: 200ms\n"))

        withClue(answered) {
            answered shouldContain """"isError":true"""
            answered shouldContain "pay"
        }
    }

    @Test
    fun `preview says what it would send`() {
        val answered = preview(mapOf("plan" to plan()), Allowance.none)

        withClue(answered) {
            answered shouldContain "3000 users"
            answered shouldContain "localhost"
        }
    }

    @Test
    fun `preview refuses what the allowance refuses, and still sends nothing`() {
        val answered = preview(mapOf("plan" to plan()), Allowance(maxRate = 1.perSecond))

        withClue(answered) {
            answered shouldContain """"isError":true"""
            answered shouldContain "refused"
        }
        arrived.get() shouldBe 0
    }

    @Test
    fun `from_openapi hands back a plan validate accepts`() {
        val written = fromOpenApi(mapOf("document" to DOCUMENT))
        withClue(written) {
            written shouldContain "getOrder"
            written shouldContain """"isError":false"""
        }
        withClue("a generated plan the next tool refuses is a generated plan nobody can use") {
            validate(mapOf("plan" to written.jsonText())) shouldContain """"isError":false"""
        }
    }

    @Test
    fun `a tool called without its argument says which one`() {
        validate(emptyMap()) shouldContain "wants a `plan`"
        fromOpenApi(emptyMap()) shouldContain "wants a `document`"
    }

    /** Reads the text back out of the MCP envelope, which is where the plan actually is. */
    private fun String.jsonText(): String = substringAfter(""""text":"""")
        .substringBeforeLast(""""}]""")
        .replace("\\n", "\n")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")

    private companion object {
        val DOCUMENT = """
            openapi: 3.1.0
            servers: [{url: "https://orders.internal"}]
            paths:
              /orders/{id}:
                get:
                  operationId: getOrder
                  parameters:
                    - {name: id, in: path, required: true, schema: {type: integer, minimum: 1, maximum: 9}}
                  responses:
                    "200": {description: one order}
                    "404": {description: no such order}
        """.trimIndent()
    }
}
