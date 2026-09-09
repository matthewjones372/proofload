package io.github.matthewjones372.proofload.http

import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.proofload.Session
import io.github.matthewjones372.proofload.StepResult
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

/**
 * A declared `404` is the service working as written. An undeclared `500` is a
 * defect. Every other load tool has to be told which is which by hand, per
 * step; a contract already knows, and this is where that knowledge lands.
 */
class DeclaredStatusTest {

    private lateinit var server: HttpServer

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/missing") { it.sendResponseHeaders(404, -1); it.close() }
        server.createContext("/broken") { it.sendResponseHeaders(500, -1); it.close() }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private val api get() = http.baseUrl("http://localhost:${server.address.port}")

    @Test
    fun `a declared status fails under its own reason`() {
        val outcome = api.get("/missing").declaring(404).run(Session.empty)

        withClue("a declared 404 is still a failure: it did not do what the step asked") {
            outcome shouldBe StepResult.Failed(Session.empty, DeclaredStatus(404))
        }
    }

    @Test
    fun `a status nobody declared is the ordinary one`() {
        val outcome = api.get("/broken").declaring(404).run(Session.empty)

        withClue("500 is the service doing something nobody wrote down") {
            outcome shouldBe StepResult.Failed(Session.empty, HttpStatus(500))
        }
    }

    @Test
    fun `declaring nothing leaves every status as it was`() {
        api.get("/missing").run(Session.empty) shouldBe StepResult.Failed(Session.empty, HttpStatus(404))
    }

    @Test
    fun `the two group apart in a report`() {
        val declared = (api.get("/missing").declaring(404).run(Session.empty) as StepResult.Failed).reason
        val undeclared = (api.get("/broken").run(Session.empty) as StepResult.Failed).reason

        withClue("a reader grouping by reason sees two rows, which is the whole point") {
            declared.described shouldBe "status 404, declared"
            undeclared.described shouldBe "status 500"
        }
    }
}
