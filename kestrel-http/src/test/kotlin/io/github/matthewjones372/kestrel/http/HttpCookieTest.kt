package io.github.matthewjones372.kestrel.http

import io.github.matthewjones372.kestrel.Session
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private fun setting(cookie: String) = Reply(200, headers = mapOf("set-cookie" to cookie))

class HttpCookieTest {

    @Test
    fun `a cookie set by one step is sent by the next`() {
        serving(
            "/login" to setting("sid=abc; Path=/; HttpOnly"),
            "/account" to Reply(200),
        ) { server ->
            val api = http.baseUrl(server.baseUrl).withCookies()

            val signedIn = api.post("/login").run(Session.empty)
            api.get("/account").run(signedIn.session)

            server.received.last().headers["cookie"] shouldBe "sid=abc"
        }
    }

    @Test
    fun `two users never see each other's cookies`() {
        serving(
            "/login/a" to setting("sid=a"),
            "/login/b" to setting("sid=b"),
            "/account" to Reply(200),
        ) { server ->
            val api = http.baseUrl(server.baseUrl).withCookies()

            val a = api.get("/login/a").run(Session.empty).session
            val b = api.get("/login/b").run(Session.empty).session
            api.get("/account").run(a)
            api.get("/account").run(b)

            server.received.filter { it.path == "/account" }.map { it.headers["cookie"] } shouldBe
                listOf("sid=a", "sid=b")
        }
    }

    @Test
    fun `a run without withCookies sends no cookie header at all`() {
        serving(
            "/login" to setting("sid=abc"),
            "/account" to Reply(200),
        ) { server ->
            val api = http.baseUrl(server.baseUrl)

            val signedIn = api.get("/login").run(Session.empty)
            api.get("/account").run(signedIn.session)

            server.received.last().headers shouldNotContainKey "cookie"
        }
    }

    @Test
    fun `cookies from several responses are all sent`() {
        serving(
            "/login" to setting("sid=abc"),
            "/cart" to setting("cart=9"),
            "/account" to Reply(200),
        ) { server ->
            val api = http.baseUrl(server.baseUrl).withCookies()

            val signedIn = api.get("/login").run(Session.empty).session
            val carted = api.get("/cart").run(signedIn).session
            api.get("/account").run(carted)

            server.received.last().headers["cookie"] shouldBe "sid=abc; cart=9"
        }
    }

    @Test
    fun `a second value for a cookie name replaces the first`() {
        serving(
            "/login" to setting("sid=first"),
            "/rotate" to setting("sid=second"),
            "/account" to Reply(200),
        ) { server ->
            val api = http.baseUrl(server.baseUrl).withCookies()

            val signedIn = api.get("/login").run(Session.empty).session
            val rotated = api.get("/rotate").run(signedIn).session
            api.get("/account").run(rotated)

            server.received.last().headers["cookie"] shouldBe "sid=second"
        }
    }

    @Test
    fun `a cookie is kept even when the status was not the one expected`() {
        serving(
            "/login" to Reply(302, headers = mapOf("set-cookie" to "sid=abc")),
            "/account" to Reply(200),
        ) { server ->
            val api = http.baseUrl(server.baseUrl).withCookies()

            val signedIn = api.post("/login").run(Session.empty)
            api.get("/account").run(signedIn.session)

            server.received.last().headers["cookie"] shouldBe "sid=abc"
        }
    }
}
