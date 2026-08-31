package io.github.matthewjones372.kestrel.websocket

import io.github.matthewjones372.kestrel.Outstanding
import io.github.matthewjones372.kestrel.Session
import io.github.matthewjones372.kestrel.StepResult
import io.github.matthewjones372.kestrel.TimedOut
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.step
import io.github.matthewjones372.kestrel.stepNames
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

private val connect = step("connect")

private val publish = step("publish")

private val tick = step("tick")

private val goodbye = step("goodbye")

private const val ANSWERS = 100

private val patience = 5.seconds

class AwaitingTest {

    @Test
    fun `a hundred answers to a hundred sends are a hundred matched and none outstanding`() {
        accepting(answering = true) { server ->
            val ids = AtomicLong()
            val watching = scenario("watching") {
                open(connect, ws.at(server.url))
                repeat(ANSWERS) { send(publish, ws.text("more"), keyedBy = { ids.incrementAndGet() }) }
                awaiting(tick, count = ANSWERS, within = patience)
                close(goodbye)
            }

            val result = watching.walk()

            result.shouldBeInstanceOf<StepResult.Ok>()
            val open = result.session[connection].shouldNotBeNull()
            open.matched shouldBe ANSWERS.toLong()
            withClue("every send was answered, so none is either lost or still moving") {
                open.outstanding(window = patience) shouldBe Outstanding.none
            }
        }
    }

    @Test
    fun `a message nobody sent for is counted as unsolicited rather than matched`() {
        accepting(pushing = 1) { server ->
            val watching = scenario("watching") {
                open(connect, ws.at(server.url))
                awaiting(tick, count = 1, within = 1.seconds)
            }

            val result = watching.walk()

            result.shouldBeInstanceOf<StepResult.Failed>()
            withClue("a push answers no send, so it does not satisfy a step waiting for one") {
                result.reason shouldBe TimedOut
            }
            val open = result.session[connection].shouldNotBeNull()
            open.unsolicited shouldBe 1L
            open.matched shouldBe 0L
        }
    }

    @Test
    fun `an awaiting on a connection the far end closed says so rather than timing out`() {
        accepting(hangingUp = true) { server ->
            val watching = scenario("watching") {
                open(connect, ws.at(server.url))
                awaiting(tick, count = 1, within = patience)
            }

            val result = watching.walk()

            result.shouldBeInstanceOf<StepResult.Failed>()
            result.reason shouldBe Disconnected
        }
    }

    @Test
    fun `an awaiting with nothing open fails the step rather than throwing`() {
        val watching = scenario("watching") { awaiting(tick, count = 1, within = patience) }

        watching.walk() shouldBe StepResult.Failed(Session.empty, NotConnected)
    }

    @Test
    fun `an awaiting is a step under the name it was declared with`() {
        val watching = scenario("watching") {
            open(connect, ws.baseUrl("ws://localhost:1").at("/stream"))
            awaiting(tick, count = 1, within = patience)
        }

        watching.stepNames shouldBe listOf("connect", "tick")
    }
}
