package io.github.matthewjones372.proofload.grpc

import io.github.matthewjones372.proofload.Progress
import io.github.matthewjones372.proofload.RunResult
import io.github.matthewjones372.proofload.TimedOut
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.engine.run
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.step
import io.grpc.ServerServiceDefinition
import io.grpc.stub.ClientCalls
import io.grpc.stub.StreamObserver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A stream is not one sample. Draining a hundred messages inside one `exec`
 * measures the hundred and the gaps between them together, which is a number
 * about neither.
 */
class StreamsTest {

    private val each = step("each")
    private val answers = step("answers")

    private fun ran(service: ServerServiceDefinition, messages: Int, within: Duration): RunResult =
        InProcess(service).use { server ->
            val orders = grpc.target("orders").over(server.managed)
            val talking = scenario("talking") {
                open(
                    orders.stream(Orders.chat) { back: StreamObserver<String> ->
                        ClientCalls.asyncBidiStreamingCall(
                            orders.channel.newCall(Orders.chat, io.grpc.CallOptions.DEFAULT),
                            back,
                        )
                    },
                )
                repeat(messages) { send(each, "anvil") }
                awaiting(answers, count = messages, within = within)
            }
            talking.at(4.perSecond, over = 250.milliseconds).run(Progress.silent)
        }

    @Test
    fun `a hundred answers to a hundred sends are a hundred samples, not one`() {
        val result = ran(Orders.serving(), messages = 100, within = 10.seconds)

        withClue("one sample per answer, so the distribution of the messages is what the report draws") {
            result[answers].ok.count shouldBe 100L
        }
        withClue("and the users that reached the step are still counted once") {
            result[answers].reached shouldBe 1L
        }
        result[each].count shouldBe 100L
    }

    @Test
    fun `each sample is measured from the message it answers, not from the step`() {
        val result = ran(Orders.serving(), messages = 100, within = 10.seconds)

        withClue("a sample of the whole batch would be a hundred times this") {
            result[answers].ok.serviceTime.max shouldBeGreaterThan Duration.ZERO
            result[answers].ok.serviceTime.max shouldBeGreaterThan result[answers].ok.serviceTime.p50
        }
    }

    @Test
    fun `a stream that stops answering part-way fails the wait, and keeps what did arrive`() {
        val result = ran(Orders.serving(answersEach = 90), messages = 100, within = 1.seconds)

        withClue("ok ${result[answers].ok.count}, failed ${result[answers].failed.count}") {
            result[answers].ok.count shouldBe 90L
            result[answers].failedWith(TimedOut) shouldBe 1L
        }
    }

    @Test
    fun `an awaiting with no open before it says so rather than waiting for nobody`() {
        val result = InProcess().use { server ->
            grpc.target("orders").over(server.managed)
            scenario("talking") { awaiting(answers, count = 1, within = 200.milliseconds) }
                .at(4.perSecond, over = 250.milliseconds)
                .run(Progress.silent)
        }

        result[answers].failedWith(NotStreaming) shouldBe 1L
    }

    @Test
    fun `a unary descriptor is refused where it is written`() {
        val why = shouldThrow<IllegalArgumentException> {
            grpc.target("orders").stream(Orders.placeOrder) { error("never") }
        }.message.orEmpty()

        withClue(why) { why shouldContain "is unary" }
    }
}
