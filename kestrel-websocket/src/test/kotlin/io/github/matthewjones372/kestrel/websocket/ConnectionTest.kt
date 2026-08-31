package io.github.matthewjones372.kestrel.websocket

import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.kestrel.Scenario
import io.github.matthewjones372.kestrel.Session
import io.github.matthewjones372.kestrel.Step
import io.github.matthewjones372.kestrel.StepResult
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.step
import io.github.matthewjones372.kestrel.stepNames
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

private val connect = step("connect")

private val disconnect = step("disconnect")

private const val ANSWER_MILLIS = 2_000L

class ConnectionTest {

    @Test
    fun `an open leaves the connection it made in the session`() {
        accepting { server ->
            val result = scenario("watching") { open(connect, ws.at(server.url)) }.walk()

            result.shouldBeInstanceOf<StepResult.Ok>()
            result.session[connection].shouldNotBeNull()
        }
    }

    @Test
    fun `an endpoint that will not upgrade fails the open, naming the failure`() {
        refusing { url ->
            val result = scenario("watching") { open(connect, ws.at(url)) }.walk()

            result shouldBe StepResult.Failed(Session.empty, "WebSocketHandshakeException")
        }
    }

    @Test
    fun `a close is a closing handshake the far end sees`() {
        accepting { server ->
            val watching = scenario("watching") {
                open(connect, ws.at(server.url))
                close(disconnect)
            }

            val result = watching.walk()

            result.shouldBeInstanceOf<StepResult.Ok>()
            withClue("the run closes every connection it made, with a Close frame and not a dropped socket") {
                server.sawClose(ANSWER_MILLIS) shouldBe true
            }
        }
    }

    @Test
    fun `a close with nothing open fails the step rather than throwing`() {
        val result = scenario("watching") { close(disconnect) }.walk()

        result shouldBe StepResult.Failed(Session.empty, NOT_CONNECTED)
    }

    @Test
    fun `the handshakes are steps under the names they were declared with`() {
        val watching = scenario("watching") {
            open(connect, ws.baseUrl("ws://localhost:1").at("/stream"))
            close(disconnect)
        }

        watching.stepNames shouldBe listOf("connect", "disconnect")
    }
}

/** Runs the steps in order, stopping at the first failure, the way the engine does. */
private fun Scenario.walk(): StepResult =
    steps.fold<Step, StepResult>(StepResult.Ok(Session.empty)) { carried, step ->
        when (carried) {
            is StepResult.Failed -> carried
            is StepResult.Ok -> (step as Step.Exec).action.run(carried.session)
        }
    }

/** A plain HTTP endpoint: reachable, and no WebSocket at the other end of it. */
private fun refusing(block: (String) -> Unit) {
    val server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
    server.createContext("/") { exchange ->
        exchange.sendResponseHeaders(200, -1)
        exchange.close()
    }
    server.start()
    try {
        block("ws://localhost:${server.address.port}")
    } finally {
        server.stop(0)
    }
}
