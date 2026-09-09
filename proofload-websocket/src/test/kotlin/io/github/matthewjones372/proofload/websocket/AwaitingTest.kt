package io.github.matthewjones372.proofload.websocket

import io.github.matthewjones372.proofload.Outstanding
import io.github.matthewjones372.proofload.Reason
import io.github.matthewjones372.proofload.Session
import io.github.matthewjones372.proofload.StepResult
import io.github.matthewjones372.proofload.TimedOut
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.step
import io.github.matthewjones372.proofload.stepNames
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val connect = step("connect")

private val publish = step("publish")

private val tick = step("tick")

private val goodbye = step("goodbye")

private val later = step("later")

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

    @Test
    fun `a hundred answers are a hundred samples, each its own message's latency`() {
        accepting(answering = true) { server ->
            val ids = AtomicLong()
            val took = mutableListOf<Duration>()
            val watching = scenario("watching") {
                open(connect, ws.at(server.url))
                repeat(ANSWERS) { send(publish, ws.text("more"), keyedBy = { ids.incrementAndGet() }) }
                awaiting(tick, count = ANSWERS, within = patience)
                close(goodbye)
            }

            watching.walk(samples = { each, _, _ -> took += each }).shouldBeInstanceOf<StepResult.Ok>()

            withClue("one per answer, not one for the batch") { took.size shouldBe ANSWERS }
            withClue("each measured from the send it answers, so none is the whole wait") {
                took.all { it < patience } shouldBe true
            }
        }
    }

    @Test
    fun `a wait that times out reports the answers that did arrive, and the failure`() {
        accepting(answering = true) { server ->
            val ids = AtomicLong()
            val took = mutableListOf<Duration>()
            // One send and one answer, but the step waits for two: the wait
            // times out with one real latency already measured.
            val watching = scenario("watching") {
                open(connect, ws.at(server.url))
                send(publish, ws.text("more"), keyedBy = { ids.incrementAndGet() })
                awaiting(tick, count = 2, within = 1.seconds)
            }

            val result = watching.walk(samples = { each, _, _ -> took += each })

            result.shouldBeInstanceOf<StepResult.Failed>().reason shouldBe TimedOut
            withClue("the one that arrived was measured rather than thrown away with the failure") {
                took.size shouldBe 2
            }
        }
    }

    @Test
    fun `a step takes the answers it waited for, not every one that had arrived`() {
        accepting(answering = true) { server ->
            val ids = AtomicLong()
            val under = mutableMapOf<String, Int>()
            // Three sends and three answers, read by two steps. On a quick
            // target all three land before the first step returns, so a step
            // that drained the queue would report three under the first name
            // and nothing under the second.
            val watching = scenario("watching") {
                open(connect, ws.at(server.url))
                repeat(3) { send(publish, ws.text("more"), keyedBy = { ids.incrementAndGet() }) }
                awaiting(tick, count = 1, within = patience)
                awaiting(later, count = 2, within = patience)
            }

            watching.walkNaming { step, _ -> under[step] = under.getOrDefault(step, 0) + 1 }
                .shouldBeInstanceOf<StepResult.Ok>()

            withClue("each step reports what it waited for: $under") {
                under["tick"] shouldBe 1
                under["later"] shouldBe 2
            }
        }
    }

    @Test
    fun `a wait that times out records the failure as a sample of its own`() {
        accepting(answering = true) { server ->
            val ids = AtomicLong()
            val reasons = mutableListOf<Reason?>()
            // One send and one answer, but the step waits for two. The body
            // has reported a sample, so the engine records none for it: if the
            // timeout does not ride a sample of its own it is recorded nowhere,
            // and the run reports one good answer and no failure at all.
            val watching = scenario("watching") {
                open(connect, ws.at(server.url))
                send(publish, ws.text("more"), keyedBy = { ids.incrementAndGet() })
                awaiting(tick, count = 2, within = 1.seconds)
            }

            watching.walk(samples = { _, _, why -> reasons += why }).shouldBeInstanceOf<StepResult.Failed>()

            withClue("the answer that arrived is a success and the one that never came is the failure") {
                reasons shouldBe listOf(null, TimedOut)
            }
        }
    }
}
