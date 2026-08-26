package io.github.matthewjones372.kestrel.http

import io.github.matthewjones372.kestrel.Session
import io.github.matthewjones372.kestrel.StepResult
import io.github.matthewjones372.kestrel.scenario
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

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
}
