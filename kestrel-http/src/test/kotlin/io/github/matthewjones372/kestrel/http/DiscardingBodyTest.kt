package io.github.matthewjones372.kestrel.http

import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.kestrel.Session
import io.github.matthewjones372.kestrel.StepResult
import io.github.matthewjones372.kestrel.StepScope
import io.github.matthewjones372.kestrel.sessionKey
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

private const val KILOBYTE = 1024

/**
 * The half of a load test most people actually hit: a service under test
 * returns more than it accepts, and every response is a `String` per user until
 * a step says nobody wants the bytes.
 *
 * The heap this runs in is smaller than the response it measures, which is the
 * claim rather than a comment about it: `kestrel-http/build.gradle.kts` sets it,
 * and a step that held this body would fail here rather than in somebody's
 * download test.
 */
class DiscardingBodyTest {

    private val orderId = sessionKey<String>("orderId")

    @Test
    fun `a response larger than the heap is measured, and the count is what the target sent`() {
        val huge = 192L * KILOBYTE * KILOBYTE
        pouring(huge) { baseUrl ->
            val scope = StepScope(Session.empty)
            val response = http.baseUrl(baseUrl).get("/exports/1").discardingBody().sendTo(scope)

            withClue("a download that returned 4 KB instead of this looks like a very fast target") {
                requireNotNull(response).bytes shouldBe huge
            }
            requireNotNull(response).body.shouldBeEmpty()
            scope.result().shouldBeInstanceOf<StepResult.Ok>()
        }
    }

    @Test
    fun `a body that was kept still knows how long it was`() {
        serving("/products" to Reply(200, "hello")) { server ->
            val response = http.baseUrl(server.baseUrl).get("/products").sendTo(StepScope(Session.empty))

            withClue("one accessor that is always true beats one that is only true sometimes") {
                requireNotNull(response).bytes shouldBe 5L
                requireNotNull(response).body shouldBe "hello"
            }
        }
    }

    @Test
    fun `a check on a discarded response is refused where it is written`() {
        val refused = shouldThrow<IllegalArgumentException> {
            api.get("/exports/1").discardingBody().checking("has rows") { true }
        }

        withClue("the message says which of the two to drop, rather than leaving a green test about nothing") {
            refused.message.orEmpty() shouldContain "check"
            refused.message.orEmpty() shouldContain "discardingBody"
        }
    }

    @Test
    fun `a capture on a discarded response is refused where it is written`() {
        val refused = shouldThrow<IllegalArgumentException> {
            api.get("/exports/1").discardingBody().capture(orderId) { it.body }
        }

        refused.message.orEmpty() shouldContain "capture"
    }

    @Test
    fun `discarding a response somebody already wrote a check on is refused too`() {
        val refused = shouldThrow<IllegalArgumentException> {
            api.get("/exports/1").checking("has rows") { true }.discardingBody()
        }

        refused.message.orEmpty() shouldContain "check"
    }

    @Test
    fun `a redirect is still followed, because a hop reads a header and not a body`() {
        serving(
            "/exports/1" to Reply(302, headers = mapOf("location" to "/exports/final")),
            "/exports/final" to Reply(200, "a".repeat(KILOBYTE)),
        ) { server ->
            val response = http.baseUrl(server.baseUrl)
                .get("/exports/1")
                .discardingBody()
                .following(1)
                .sendTo(StepScope(Session.empty))

            requireNotNull(response).status shouldBe 200
            requireNotNull(response).bytes shouldBe KILOBYTE.toLong()
        }
    }

    private val api: Http get() = http.baseUrl("http://localhost:1")

    /**
     * A server that writes [bytes] a buffer at a time, holding none of it.
     *
     * `Reply` carries its body as a `String`, so a large answer built that way
     * would run this test's own JVM out of heap before the client could.
     */
    private fun pouring(bytes: Long, block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        val buffer = ByteArray(KILOBYTE * KILOBYTE) { 'a'.code.toByte() }
        server.createContext("/exports") { exchange ->
            exchange.sendResponseHeaders(200, bytes)
            exchange.responseBody.use { out ->
                var left = bytes
                while (left > 0) {
                    val take = minOf(left, buffer.size.toLong()).toInt()
                    out.write(buffer, 0, take)
                    left -= take
                }
            }
        }
        server.start()
        try {
            block("http://localhost:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }
}
