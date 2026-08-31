package io.github.matthewjones372.kestrel.websocket

import io.github.matthewjones372.kestrel.Outstanding
import io.github.matthewjones372.kestrel.Session
import io.github.matthewjones372.kestrel.StepResult
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.sessionKey
import io.github.matthewjones372.kestrel.step
import io.github.matthewjones372.kestrel.stepNames
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

private val connect = step("connect")

private val subscribe = step("subscribe")

private val disconnect = step("disconnect")

private val requestId = sessionKey<Long>("requestId")

private const val ANSWER_MILLIS = 2_000L

class SendTest {

    @Test
    fun `a send is written and does not wait for an answer`() {
        accepting { server ->
            val watching = scenario("watching") {
                open(connect, ws.at(server.url))
                send(subscribe, ws.text("""{"symbol":"ANVIL"}"""), keyedBy = { 1L })
                close(disconnect)
            }

            val result = watching.walk()

            result.shouldBeInstanceOf<StepResult.Ok>()
            withClue("the frame left, though nothing on this server ever answers one") {
                server.sawMessages(count = 1, millis = ANSWER_MILLIS) shouldBe listOf(TEXT_OPCODE)
            }
        }
    }

    @Test
    fun `a binary frame is sent as a binary frame`() {
        accepting { server ->
            val watching = scenario("watching") {
                open(connect, ws.at(server.url))
                send(subscribe, ws.binary(byteArrayOf(1, 2, 3)), keyedBy = { 1L })
                close(disconnect)
            }

            watching.walk().shouldBeInstanceOf<StepResult.Ok>()
            server.sawMessages(count = 1, millis = ANSWER_MILLIS) shouldBe listOf(BINARY_OPCODE)
        }
    }

    @Test
    fun `a send carries the correlation the caller keyed it by`() {
        accepting { server ->
            val keyed = mutableListOf<Long>()
            val watching = scenario("watching") {
                open(connect, ws.at(server.url))
                exec(step("identify")) { set(requestId, 7L) }
                send(subscribe, ws.text("hello"), keyedBy = { session -> (session[requestId] ?: 0L).also(keyed::add) })
            }

            val result = watching.walk()

            keyed shouldBe listOf(7L)
            withClue("the send is a departure the connection is still owed an answer for") {
                result.session[connection].shouldNotBeNull()
                    .outstanding(window = 1.seconds) shouldBe Outstanding(unmatched = 0, inFlight = 1)
            }
        }
    }

    @Test
    fun `a send with nothing open fails the step rather than throwing`() {
        val watching = scenario("watching") { send(subscribe, ws.text("hello"), keyedBy = { 1L }) }

        watching.walk() shouldBe StepResult.Failed(Session.empty, NotConnected)
    }

    @Test
    fun `a send is a step under the name it was declared with`() {
        val watching = scenario("watching") {
            open(connect, ws.baseUrl("ws://localhost:1").at("/stream"))
            send(subscribe, ws.text("hello"), keyedBy = { 1L })
        }

        watching.stepNames shouldBe listOf("connect", "subscribe")
    }
}
