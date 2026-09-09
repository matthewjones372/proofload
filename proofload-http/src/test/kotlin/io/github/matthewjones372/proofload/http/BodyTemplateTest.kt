package io.github.matthewjones372.proofload.http

import io.github.matthewjones372.proofload.Session
import io.github.matthewjones372.proofload.StepResult
import io.github.matthewjones372.proofload.sessionKey
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

private val cart = sessionKey<String>("cart")

/**
 * Ten thousand users sending one identical order measure whatever the target
 * does with a duplicate, and the page calls it the latency of placing an order.
 */
class BodyTemplateTest {

    @Test
    fun `a body is filled from the session, as a path already is`() {
        serving("/orders" to Reply(201)) { server ->
            val result = http.baseUrl(server.baseUrl)
                .post("/orders")
                .body("""{"cart":"{cart}"}""")
                .expecting(201)
                .run(Session.empty.set(cart, "1 anvil"))

            result.shouldBeInstanceOf<StepResult.Ok>()
            server.received.single().body shouldBe """{"cart":"1 anvil"}"""
        }
    }

    @Test
    fun `a body with no braces arrives exactly as it was written`() {
        serving("/orders" to Reply(201)) { server ->
            http.baseUrl(server.baseUrl)
                .post("/orders")
                .body("""{"cart":"1 anvil"}""")
                .expecting(201)
                .run(Session.empty)

            withClue("a JSON body is full of braces that are not placeholders") {
                server.received.single().body shouldBe """{"cart":"1 anvil"}"""
            }
        }
    }

    @Test
    fun `a nested JSON body is not one placeholder named after its own contents`() {
        serving("/orders" to Reply(201)) { server ->
            val nested = """{"cart":{"lines":[{"sku":"anvil"}]},"for":"{cart}"}"""
            http.baseUrl(server.baseUrl)
                .post("/orders")
                .body(nested)
                .expecting(201)
                .run(Session.empty.set(cart, "acme"))

            withClue("only the identifier is a hole; the document's own braces are content") {
                server.received.single().body shouldBe
                    """{"cart":{"lines":[{"sku":"anvil"}]},"for":"acme"}"""
            }
        }
    }

    @Test
    fun `a placeholder the session has nothing under fails the step, naming the key`() {
        serving("/orders" to Reply(201)) { server ->
            val result = http.baseUrl(server.baseUrl)
                .post("/orders")
                .body("""{"cart":"{cart}"}""")
                .expecting(201)
                .run(Session.empty)

            result shouldBe StepResult.Failed(Session.empty, UnfilledPath("cart"))
            withClue("and nothing was sent: a body with a hole in it is not a request to make") {
                server.received shouldBe emptyList()
            }
        }
    }
}
