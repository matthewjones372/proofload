package io.github.matthewjones372.proofload.http

import io.github.matthewjones372.proofload.Session
import io.github.matthewjones372.proofload.StepResult
import io.github.matthewjones372.proofload.TimedOut
import io.github.matthewjones372.proofload.sessionKey
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * The seam exists so a faster client can be handed in without moving anything
 * that decides what a number means. These tests send through a double that
 * touches no socket, which is the other thing the seam buys.
 */
class TransportSeamTest {

    /** Records what it was asked to send and answers whatever it was told to. */
    private class Recording(private val answer: (Request) -> Exchange) : Transport {

        val sent = mutableListOf<Request>()

        override fun exchange(request: Request): Exchange {
            sent += request
            return answer(request)
        }
    }

    private fun answering(status: Int = 200, body: String = "", headers: List<Pair<String, String>> = emptyList()) =
        Recording { Exchange.Answered(Response(status, headers, body)) }

    @Test
    fun `an action sends through the transport it was given, and no socket is opened`() {
        val transport = answering()

        val result = http.baseUrl("http://nowhere.invalid").over(transport).get("/pay").run(Session.empty)

        result.shouldBeInstanceOf<StepResult.Ok>()
        transport.sent.map { it.uri.toString() } shouldContainExactly listOf("http://nowhere.invalid/pay")
    }

    @Test
    fun `two Http values in one run send through two transports`() {
        val one = answering()
        val other = answering()

        http.baseUrl("http://one.invalid").over(one).get("/a").run(Session.empty)
        http.baseUrl("http://other.invalid").over(other).get("/b").run(Session.empty)

        one.sent.single().uri.path shouldBe "/a"
        other.sent.single().uri.path shouldBe "/b"
    }

    @Test
    fun `a failure the transport reports fails the step under that reason`() {
        val giving = Recording { Exchange.Failed(TimedOut) }

        val result = http.baseUrl("http://nowhere.invalid").over(giving).get("/pay").run(Session.empty)

        result.shouldBeInstanceOf<StepResult.Failed>().reason shouldBe TimedOut
    }

    @Test
    fun `the redirect walk stays above the seam, so a hop is another request through it`() {
        val hopping = Recording { request ->
            if (request.uri.path == "/in") {
                Exchange.Answered(Response(302, listOf("location" to "/home"), ""))
            } else {
                Exchange.Answered(Response(200, emptyList(), "home"))
            }
        }

        val result = http.baseUrl("http://nowhere.invalid").over(hopping).get("/in").following().run(Session.empty)

        result.shouldBeInstanceOf<StepResult.Ok>().attempts shouldBe 2
        hopping.sent.map { it.uri.path } shouldContainExactly listOf("/in", "/home")
    }

    @Test
    fun `the cookie jar stays above the seam`() {
        val setting = Recording { request ->
            if (request.uri.path == "/in") {
                Exchange.Answered(Response(200, listOf("set-cookie" to "session=abc; Path=/"), ""))
            } else {
                Exchange.Answered(Response(200, emptyList(), ""))
            }
        }
        val api = http.baseUrl("http://nowhere.invalid").withCookies().over(setting)

        val signedIn = api.get("/in").run(Session.empty).shouldBeInstanceOf<StepResult.Ok>()
        api.get("/next").run(signedIn.session)

        withClue("the jar is the user's, and the transport never sees it as state") {
            setting.sent.last().headers["cookie"] shouldContain "session=abc"
        }
    }

    @Test
    fun `the trace headers stay above the seam`() {
        val tracing = answering()

        http.baseUrl("http://nowhere.invalid").traced().over(tracing).get("/pay").run(Session.empty)

        val headers = tracing.sent.single().headers
        headers.getValue("traceparent") shouldMatch Regex("00-[0-9a-f]{32}-[0-9a-f]{16}-00")
        headers.getValue("baggage") shouldBe "synthetic=true"
    }

    @Test
    fun `a captured value still comes out of the response the transport gave`() {
        val id = sessionKey<String>("id")
        val answering = answering(headers = listOf("location" to "/orders/9f3"))

        val result = http.baseUrl("http://nowhere.invalid").over(answering)
            .get("/orders")
            .capture(id) { it.header("location") }
            .run(Session.empty)

        result.shouldBeInstanceOf<StepResult.Ok>().session[id] shouldBe "/orders/9f3"
    }
}
