package io.github.matthewjones372.kestrel.http

import io.github.matthewjones372.kestrel.Session
import io.github.matthewjones372.kestrel.StepResult
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.stepNames
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration

class HttpRequestTest {

    @Test
    fun `a post with a body passes when it gets the status it expects`() {
        serving("/orders" to Reply(201)) { server ->
            val result = http.baseUrl(server.baseUrl)
                .post("/orders")
                .header("content-type", "application/json")
                .body("""{"cart":"1 anvil"}""")
                .expecting(201)
                .run(Session.empty)

            result shouldBe StepResult.Ok(Session.empty)
            val received = server.received.single()
            received.method shouldBe "POST"
            received.body shouldBe """{"cart":"1 anvil"}"""
            received.headers["content-type"] shouldBe "application/json"
        }
    }

    @Test
    fun `the same post against a 200 fails, naming the status it got`() {
        serving("/orders" to Reply(200)) { server ->
            val result = http.baseUrl(server.baseUrl)
                .post("/orders")
                .body("""{"cart":"1 anvil"}""")
                .expecting(201)
                .run(Session.empty)

            result shouldBe StepResult.Failed(Session.empty, "status 200")
        }
    }

    @Test
    fun `a request expects 200 unless it says otherwise`() {
        serving("/products" to Reply(204)) { server ->
            val result = http.baseUrl(server.baseUrl).get("/products").run(Session.empty)

            result shouldBe StepResult.Failed(Session.empty, "status 204")
        }
    }

    @Test
    fun `the base url is the prefix, and the step name is still the path`() {
        serving("/products" to Reply(200)) { server ->
            val products = http.baseUrl(server.baseUrl).get("/products")
            val browse = scenario("browse") { exec(products) }

            products.run(Session.empty) shouldBe StepResult.Ok(Session.empty)
            browse.stepNames.single() shouldBe "/products"
            server.received.single().path shouldBe "/products"
        }
    }

    @Test
    fun `a trailing slash on the base url does not double up`() {
        serving("/products" to Reply(200)) { server ->
            http.baseUrl("${server.baseUrl}/").get("/products").run(Session.empty)

            server.received.single().path shouldBe "/products"
        }
    }

    @Test
    fun `each method reaches the server as itself`() {
        serving("/thing" to Reply(200)) { server ->
            val api = http.baseUrl(server.baseUrl)
            val requests = listOf(
                api.get("/thing"),
                api.post("/thing"),
                api.put("/thing"),
                api.patch("/thing"),
                api.delete("/thing"),
                api.head("/thing"),
            )

            requests.map { it.run(Session.empty) } shouldBe List(requests.size) { StepResult.Ok(Session.empty) }
            server.received.map { it.method } shouldBe listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")
        }
    }

    @Test
    fun `a redirect is a status, not a detour`() {
        serving("/products" to Reply(302, headers = mapOf("location" to "/elsewhere"))) { server ->
            val result = http.baseUrl(server.baseUrl).get("/products").run(Session.empty)

            result shouldBe StepResult.Failed(Session.empty, "status 302")
            server.received.map { it.path } shouldBe listOf("/products")
        }
    }

    @Test
    fun `a request that outlives its timeout fails as a timeout, not as a class name`() {
        serving("/slow" to Reply(200, delayMillis = 500)) { server ->
            val result = http.baseUrl(server.baseUrl)
                .get("/slow")
                .timeout(Duration.ofMillis(50))
                .run(Session.empty)

            result shouldBe StepResult.Failed(Session.empty, "timeout")
        }
    }
}
