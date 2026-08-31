package io.github.matthewjones372.kestrel.http

import io.github.matthewjones372.kestrel.Session
import io.github.matthewjones372.kestrel.StepResult
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private fun redirectTo(location: String, status: Int = 302, cookie: String? = null) = Reply(
    status,
    headers = mapOf("location" to location) + cookie?.let { mapOf("set-cookie" to it) }.orEmpty(),
)

class HttpRedirectTest {

    @Test
    fun `a redirect is followed to the page it points at`() {
        serving(
            "/login" to redirectTo("/account"),
            "/account" to Reply(200, "welcome"),
        ) { server ->
            val api = http.baseUrl(server.baseUrl)

            val result = api.post("/login").following().run(Session.empty)

            result shouldBe StepResult.Ok(Session.empty)
            server.received.map { it.path } shouldBe listOf("/login", "/account")
        }
    }

    @Test
    fun `a one-hop redirect is one step and two round trips`() {
        serving(
            "/login" to redirectTo("/account"),
            "/account" to Reply(200),
        ) { server ->
            val api = http.baseUrl(server.baseUrl)

            api.post("/login").following().run(Session.empty)

            server.received.size shouldBe 2
        }
    }

    @Test
    fun `a chain longer than max fails, named for the limit it hit`() {
        serving(
            "/a" to redirectTo("/b"),
            "/b" to redirectTo("/c"),
            "/c" to Reply(200),
        ) { server ->
            val api = http.baseUrl(server.baseUrl)

            val result = api.get("/a").following().run(Session.empty)

            result shouldBe StepResult.Failed(Session.empty, TooManyRedirects(1))
        }
    }

    @Test
    fun `a chain within max is followed to the end`() {
        serving(
            "/a" to redirectTo("/b"),
            "/b" to redirectTo("/c"),
            "/c" to Reply(200),
        ) { server ->
            val api = http.baseUrl(server.baseUrl)

            val result = api.get("/a").following(max = 2).run(Session.empty)

            result shouldBe StepResult.Ok(Session.empty)
            server.received.map { it.path } shouldBe listOf("/a", "/b", "/c")
        }
    }

    @Test
    fun `a redirect nobody asked to follow is still the failure it was`() {
        serving("/login" to redirectTo("/account")) { server ->
            val api = http.baseUrl(server.baseUrl)

            val result = api.post("/login").run(Session.empty)

            result shouldBe StepResult.Failed(Session.empty, HttpStatus(302))
            server.received.map { it.path } shouldBe listOf("/login")
        }
    }

    @Test
    fun `the status at the end of the chain is the one the step is judged on`() {
        serving(
            "/login" to redirectTo("/account"),
            "/account" to Reply(500),
        ) { server ->
            val api = http.baseUrl(server.baseUrl)

            val result = api.post("/login").following().run(Session.empty)

            result shouldBe StepResult.Failed(Session.empty, HttpStatus(500))
        }
    }

    @Test
    fun `a cookie set with the redirect is sent on the hop it points at`() {
        serving(
            "/login" to redirectTo("/account", cookie = "sid=abc"),
            "/account" to Reply(200),
        ) { server ->
            val api = http.baseUrl(server.baseUrl).withCookies()

            api.post("/login").following().run(Session.empty)

            server.received.last().headers["cookie"] shouldBe "sid=abc"
        }
    }

    @Test
    fun `a 302 is followed as a GET without the body`() {
        serving(
            "/login" to redirectTo("/account"),
            "/account" to Reply(200),
        ) { server ->
            val api = http.baseUrl(server.baseUrl)

            api.post("/login").body("user=ada").following().run(Session.empty)

            server.received.last().method shouldBe "GET"
            server.received.last().body shouldBe ""
        }
    }

    @Test
    fun `a 307 keeps the method and the body`() {
        serving(
            "/login" to redirectTo("/session", status = 307),
            "/session" to Reply(200),
        ) { server ->
            val api = http.baseUrl(server.baseUrl)

            api.post("/login").body("user=ada").following().run(Session.empty)

            server.received.last().method shouldBe "POST"
            server.received.last().body shouldBe "user=ada"
        }
    }

    @Test
    fun `an absolute Location is followed as given`() {
        serving("/account" to Reply(200)) { destination ->
            serving("/login" to redirectTo("${destination.baseUrl}/account")) { origin ->
                val result = http.baseUrl(origin.baseUrl).get("/login").following().run(Session.empty)

                result shouldBe StepResult.Ok(Session.empty)
                destination.received.map { it.path } shouldBe listOf("/account")
            }
        }
    }

    @Test
    fun `a check reads the page the chain landed on`() {
        serving(
            "/login" to redirectTo("/account"),
            "/account" to Reply(200, "welcome"),
        ) { server ->
            val api = http.baseUrl(server.baseUrl)

            val result = api.post("/login")
                .following()
                .checking("landed on the account page") { it.body == "welcome" }
                .run(Session.empty)

            result shouldBe StepResult.Ok(Session.empty)
        }
    }
}
