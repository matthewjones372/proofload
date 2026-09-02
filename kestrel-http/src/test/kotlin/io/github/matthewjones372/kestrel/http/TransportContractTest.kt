package io.github.matthewjones372.kestrel.http

import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.kestrel.Threw
import io.github.matthewjones372.kestrel.TimedOut
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What a transport owes, whatever it is built on.
 *
 * Written against the shipped one and meant to be run against any other: a
 * transport that passes this cannot change what a run measures, only how fast
 * it can be measured. It is the answer to "is this replacement honest".
 */
class TransportContractTest {

    private lateinit var server: HttpServer

    private val transport: Transport = JdkHttpClient()

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/ok") { exchange ->
            val body = "hello".toByteArray()
            exchange.responseHeaders.add("x-echo", exchange.requestMethod)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/refused") { exchange ->
            val body = "no".toByteArray()
            exchange.sendResponseHeaders(503, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/silent") { Thread.sleep(2_000) }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun to(path: String, timeout: kotlin.time.Duration = 5.seconds, method: String = "GET") = Request(
        method = method,
        uri = URI.create("http://localhost:${server.address.port}$path"),
        timeout = timeout,
    )

    @Test
    fun `an answer is Answered, whatever the status`() {
        val answered = transport.exchange(to("/ok")).shouldBeInstanceOf<Exchange.Answered>()

        answered.response.status shouldBe 200
        answered.response.body shouldBe "hello"
        answered.response.header("x-echo") shouldBe "GET"
    }

    @Test
    fun `a 503 is an answer and not a failure, because the target answered`() {
        val answered = transport.exchange(to("/refused")).shouldBeInstanceOf<Exchange.Answered>()

        withClue("a status is the step's to judge, not the transport's") {
            answered.response.status shouldBe 503
        }
    }

    @Test
    fun `a target that does not answer in time is Failed with TimedOut`() {
        val failed = transport.exchange(to("/silent", timeout = 100.milliseconds))
            .shouldBeInstanceOf<Exchange.Failed>()

        failed.reason shouldBe TimedOut
    }

    @Test
    fun `a refused connection is Failed, named for what was thrown`() {
        val closed = ServerSocket(0).also { it.close() }
        val nowhere = Request(
            method = "GET",
            uri = URI.create("http://localhost:${closed.localPort}/ok"),
            timeout = 2.seconds,
        )

        val failed = transport.exchange(nowhere).shouldBeInstanceOf<Exchange.Failed>()

        failed.reason shouldBe Threw("ConnectException")
    }

    @Test
    fun `the headers and body it was given are what the target receives`() {
        val posted = to("/ok", method = "POST").copy(
            headers = mapOf("x-asked" to "yes"),
            body = """{"a":1}""",
        )

        val answered = transport.exchange(posted).shouldBeInstanceOf<Exchange.Answered>()

        answered.response.header("x-echo") shouldBe "POST"
    }

    @Test
    fun `a transport never follows a redirect of its own accord`() {
        server.createContext("/away") { exchange ->
            exchange.responseHeaders.add("location", "/ok")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }

        val answered = transport.exchange(to("/away")).shouldBeInstanceOf<Exchange.Answered>()

        withClue("the walk is the step's, so a hop the scenario did not ask for stays a finding") {
            answered.response.status shouldBe 302
        }
    }
}
