package io.github.matthewjones372.kestrel.http

import io.github.matthewjones372.kestrel.Session
import io.github.matthewjones372.kestrel.StepResult
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.sessionKey
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class HttpActionTest {

    @Test
    fun `a get against a served path is ok`() {
        serving("/products" to Reply(200, "[]")) { server ->
            val result = http.get("${server.baseUrl}/products").run(Session.empty)

            result shouldBe StepResult.Ok(Session.empty)
        }
    }

    @Test
    fun `a status the request did not expect fails the step, naming it`() {
        serving("/products" to Reply(500)) { server ->
            val result = http.get("${server.baseUrl}/products").run(Session.empty)

            result shouldBe StepResult.Failed(Session.empty, "status 500")
        }
    }

    @Test
    fun `a transport error fails the step with the exception's class name`() {
        val result = http.get("${closedPortUrl()}/products").run(Session.empty)

        result shouldBe StepResult.Failed(Session.empty, "ConnectException")
    }

    @Test
    fun `the step name is the path as written`() {
        val checkout = scenario("checkout") {
            exec(http.get("/products"))
        }

        checkout.steps.single().name shouldBe "/products"
    }

    @Test
    fun `the request reaches the server as a GET`() {
        serving("/products" to Reply(200)) { server ->
            http.get("${server.baseUrl}/products").run(Session.empty)

            server.received.single().method shouldBe "GET"
        }
    }

    @Test
    fun `a response the check rejects fails the step under the check's name`() {
        serving("/orders" to Reply(200, "{}")) { server ->
            val result = http.post("${server.baseUrl}/orders")
                .checking("has an id") { response -> "\"id\"" in response.body }
                .run(Session.empty)

            result shouldBe StepResult.Failed(Session.empty, "has an id")
        }
    }

    @Test
    fun `a response the check accepts is ok`() {
        serving("/orders" to Reply(200, """{"id":"7"}""")) { server ->
            val result = http.post("${server.baseUrl}/orders")
                .checking("has an id") { response -> "\"id\"" in response.body }
                .run(Session.empty)

            result shouldBe StepResult.Ok(Session.empty)
        }
    }

    @Test
    fun `a check the status already failed is not asked`() {
        serving("/orders" to Reply(500, "{}")) { server ->
            val asked = AtomicInteger()

            val result = http.post("${server.baseUrl}/orders")
                .checking("has an id") { asked.incrementAndGet() > 0 }
                .run(Session.empty)

            result shouldBe StepResult.Failed(Session.empty, "status 500")
            asked.get() shouldBe 0
        }
    }

    @Test
    fun `nothing is captured out of a response a check rejected`() {
        serving("/orders" to Reply(200, "{}")) { server ->
            val id = sessionKey<String>("orderId")

            val result = http.post("${server.baseUrl}/orders")
                .checking("has an id") { response -> "\"id\"" in response.body }
                .capture(id) { "7" }
                .run(Session.empty)

            result shouldBe StepResult.Failed(Session.empty, "has an id")
        }
    }

    @Test
    fun `every check reads the one response the request sent for`() {
        serving("/orders" to Reply(200, """{"id":"7"}""")) { server ->
            http.post("${server.baseUrl}/orders")
                .checking("has an id") { response -> "\"id\"" in response.body }
                .checking("is json") { response -> response.body.startsWith("{") }
                .run(Session.empty)

            server.received.size shouldBe 1
        }
    }
}
