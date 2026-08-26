package io.github.matthewjones372.kestrel.http

import io.github.matthewjones372.kestrel.Session
import io.github.matthewjones372.kestrel.Step
import io.github.matthewjones372.kestrel.StepResult
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.sessionKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

private val orderId = sessionKey<String>("orderId")
private val orders = sessionKey<Int>("orders")

class HttpSessionTest {

    @Test
    fun `a path template fills from the session`() {
        serving("/orders/7" to Reply(200)) { server ->
            val session = Session.empty.set(orderId, "7")

            val result = http.baseUrl(server.baseUrl).get("/orders/{orderId}").run(session)

            result shouldBe StepResult.Ok(session)
            server.received.single().path shouldBe "/orders/7"
        }
    }

    @Test
    fun `the step's recorded name is the template, not the url it sent`() {
        serving("/orders/7" to Reply(200)) { server ->
            val fetch = http.baseUrl(server.baseUrl).get("/orders/{orderId}")
            val checkout = scenario("checkout") { exec(fetch) }

            fetch.run(Session.empty.set(orderId, "7"))

            checkout.steps.single().name shouldBe "/orders/{orderId}"
        }
    }

    @Test
    fun `a template with nothing in the session to fill it fails the step`() {
        serving("/orders/7" to Reply(200)) { server ->
            val result = http.baseUrl(server.baseUrl).get("/orders/{orderId}").run(Session.empty)

            result shouldBe StepResult.Failed(Session.empty, "missing {orderId}")
            server.received shouldBe emptyList()
        }
    }

    @Test
    fun `a capture writes a response value back into the session`() {
        val reply = Reply(201, headers = mapOf("location" to "/orders/7"))
        serving("/orders" to reply) { server ->
            val checkout = scenario("checkout") {
                exec("pay") {
                    send(
                        http.baseUrl(server.baseUrl)
                            .post("/orders")
                            .header("content-type", "application/json")
                            .body("""{"cart":"1 anvil"}""")
                            .expecting(201)
                            .capture(orderId) { response -> response.header("location") },
                    )
                }
            }

            val result = checkout.steps.single().shouldBeInstanceOf<Step.Exec>().action.run(Session.empty)

            result shouldBe StepResult.Ok(Session.empty.set(orderId, "/orders/7"))
        }
    }

    @Test
    fun `a capture keeps the type its key declares`() {
        serving("/orders" to Reply(200, "12")) { server ->
            val result = http.baseUrl(server.baseUrl)
                .get("/orders")
                .capture(orders) { response -> response.body.toIntOrNull() }
                .run(Session.empty)

            result.session[orders] shouldBe 12
        }
    }

    @Test
    fun `a capture that finds nothing fails the step rather than passing it on`() {
        serving("/orders" to Reply(201)) { server ->
            val result = http.baseUrl(server.baseUrl)
                .post("/orders")
                .expecting(201)
                .capture(orderId) { response -> response.header("location") }
                .run(Session.empty)

            result shouldBe StepResult.Failed(Session.empty, "no orderId captured")
        }
    }

    @Test
    fun `nothing is captured out of a response the request did not expect`() {
        val reply = Reply(500, headers = mapOf("location" to "/errors/1"))
        serving("/orders" to reply) { server ->
            val result = http.baseUrl(server.baseUrl)
                .post("/orders")
                .expecting(201)
                .capture(orderId) { response -> response.header("location") }
                .run(Session.empty)

            result shouldBe StepResult.Failed(Session.empty, "status 500")
        }
    }
}
