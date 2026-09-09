package io.github.matthewjones372.proofload.http

import io.github.matthewjones372.proofload.Session
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test

/** The wire format from W3C Trace Context: version, trace id, parent id, flags. */
private val TRACEPARENT = Regex("^00-[0-9a-f]{32}-[0-9a-f]{16}-00$")

private const val REQUESTS = 64

class TraceparentTest {

    @Test
    fun `a traced client sends a well-formed traceparent`() {
        serving("/products" to Reply(200)) { server ->
            http.baseUrl(server.baseUrl).traced().get("/products").run(Session.empty)

            server.received.single().headers["traceparent"].orEmpty() shouldMatch TRACEPARENT
        }
    }

    @Test
    fun `neither id is the all-zero one the format forbids`() {
        serving("/products" to Reply(200)) { server ->
            val api = http.baseUrl(server.baseUrl).traced()
            repeat(REQUESTS) { api.get("/products").run(Session.empty) }

            val ids = server.received.map { it.headers.getValue("traceparent").split("-") }
            ids.map { it[1] }.none { it == "0".repeat(32) } shouldBe true
            ids.map { it[2] }.none { it == "0".repeat(16) } shouldBe true
        }
    }

    @Test
    fun `every request gets its own trace id and its own parent id`() {
        serving("/products" to Reply(200)) { server ->
            val api = http.baseUrl(server.baseUrl).traced()
            repeat(REQUESTS) { api.get("/products").run(Session.empty) }

            val ids = server.received.map { it.headers.getValue("traceparent").split("-") }
            ids.map { it[1] }.distinct() shouldHaveSize REQUESTS
            ids.map { it[2] }.distinct() shouldHaveSize REQUESTS
        }
    }

    @Test
    fun `a traced request marks itself as load, so downstream can tell it apart`() {
        serving("/products" to Reply(200)) { server ->
            http.baseUrl(server.baseUrl).traced().post("/products").run(Session.empty)

            server.received.single().headers["baggage"] shouldBe "synthetic=true"
        }
    }

    @Test
    fun `tracing survives the headers a request carries of its own`() {
        serving("/products" to Reply(200)) { server ->
            http.baseUrl(server.baseUrl).traced()
                .get("/products")
                .header("x-tenant", "anvils")
                .run(Session.empty)

            val received = server.received.single()
            received.headers["x-tenant"] shouldBe "anvils"
            received.headers["traceparent"].orEmpty() shouldMatch TRACEPARENT
        }
    }

    /**
     * The two switches were written on branches that could not see each other,
     * so this is the first thing to ask either of them together.
     */
    @Test
    fun `tracing and cookies are both kept, in whichever order they are asked for`() {
        // Asserted on the value rather than only on the wire: a builder that
        // drops the other's switch is the failure, and only one of the two
        // shows up in a header.
        listOf(
            http.baseUrl("http://localhost:1").traced().withCookies(),
            http.baseUrl("http://localhost:1").withCookies().traced(),
        ).forEach { client ->
            withClue("traced then cookies, and the reverse") {
                client.traced shouldBe true
                client.cookies shouldBe true
            }
        }

        serving("/products" to Reply(200)) { server ->
            http.baseUrl(server.baseUrl).traced().withCookies().get("/products").run(Session.empty)

            server.received.single().headers["traceparent"].orEmpty() shouldMatch TRACEPARENT
        }
    }

    @Test
    fun `an untraced client sends neither header`() {
        serving("/products" to Reply(200)) { server ->
            http.baseUrl(server.baseUrl).get("/products").run(Session.empty)

            val received = server.received.single()
            received.headers["traceparent"].shouldBeNull()
            received.headers["baggage"].shouldBeNull()
        }
    }
}
