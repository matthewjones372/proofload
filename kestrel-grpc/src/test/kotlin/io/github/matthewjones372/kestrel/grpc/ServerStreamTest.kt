package io.github.matthewjones372.kestrel.grpc

import io.github.matthewjones372.kestrel.Progress
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.engine.run
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.step
import io.grpc.CallOptions
import io.grpc.stub.ClientCalls
import io.grpc.stub.StreamObserver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A server stream's messages answer no send of their own, so there are two
 * honest readings of them and they are not the same number: how long the call
 * took to say anything, and how long it left between the things it said. One
 * histogram holding both averages into a figure describing neither.
 */
class ServerStreamTest {

    private val first = step("first")
    private val gaps = step("gaps")

    private val push = step("push")

    private val watching = 5

    private fun watched(
        build: io.github.matthewjones372.kestrel.ScenarioBuilder.(GrpcServerStream<String, String>) -> Unit,
    ): RunResult = InProcess(Orders.serving(fills = watching)).use { server ->
        val orders = grpc.target("orders").over(server.managed)
        val fills = orders.serverStream(Orders.watchFills) { request, back ->
            ClientCalls.asyncServerStreamingCall(
                orders.channel.newCall(Orders.watchFills, CallOptions.DEFAULT),
                request,
                back,
            )
        }
        scenario("watching") { build(fills) }
            .at(4.perSecond, over = 250.milliseconds)
            .run(Progress.silent)
    }

    @Test
    fun `a stream of five is one round trip and four gaps, under their own names`() {
        val result = watched { fills ->
            open(fills, request = "all")
            firstAnswer(first, within = 5.seconds)
            cadence(gaps, count = watching - 1, within = 5.seconds)
        }

        withClue("the round trip is one sample, whatever the stream went on to say") {
            result[first].count shouldBe 1L
        }
        withClue("and every message after it is a gap") {
            result[gaps].count shouldBe (watching - 1).toLong()
        }
    }

    @Test
    fun `a gap is measured from the message before it, not from the call`() {
        val result = watched { fills ->
            open(fills, request = "all")
            firstAnswer(first, within = 5.seconds)
            cadence(gaps, count = watching - 1, within = 5.seconds)
        }

        withClue("the server waits before its first message and not before the rest") {
            result[gaps].serviceTime.max shouldBeLessThan result[first].serviceTime.p50
        }
    }

    @Test
    fun `a cadence with no first answer before it is refused rather than reporting a round trip`() {
        val result = watched { fills ->
            open(fills, request = "all")
            cadence(gaps, count = watching - 1, within = 5.seconds)
        }

        withClue("handing the round trip out as a gap is the one mistake nobody could catch downstream") {
            result[gaps].failedWith(NoFirstAnswer) shouldBe 1L
        }
    }

    @Test
    fun `a send on a server stream is refused, its one request having gone with the call`() {
        val result = watched { fills ->
            open(fills, request = "all")
            send(push, "more")
        }

        result[push].failedWith(NotSending) shouldBe 1L
    }

    @Test
    fun `a bidirectional descriptor is refused where a server stream is written`() {
        val why = shouldThrow<IllegalArgumentException> {
            grpc.target("orders").serverStream(Orders.chat) { _: String, _: StreamObserver<String> -> }
        }.message.orEmpty()

        withClue(why) { why shouldContain "is not server streaming" }
    }

    @Test
    fun `a server-streaming descriptor is refused where a two-way stream is written`() {
        val why = shouldThrow<IllegalArgumentException> {
            grpc.target("orders").stream(Orders.watchFills) { error("never") }
        }.message.orEmpty()

        withClue(why) { why shouldContain "answers no send" }
    }
}
