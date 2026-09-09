package io.github.matthewjones372.proofload.grpc

import io.github.matthewjones372.proofload.Progress
import io.github.matthewjones372.proofload.RunResult
import io.github.matthewjones372.proofload.Threw
import io.github.matthewjones372.proofload.TimedOut
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.engine.run
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.scenario
import io.grpc.ManagedChannelBuilder
import io.grpc.Status
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import kotlin.time.Duration.Companion.milliseconds

/**
 * Every failure was one `Threw("StatusRuntimeException")` row, so the question
 * 0063 exists to let a reader ask — was that the target saying no, or the
 * socket giving up — could not be asked at all.
 */
class GrpcStatusTest {

    private fun ranAgainst(status: Status): RunResult =
        InProcess(Orders.serving(failing = status)).use { server ->
            val orders = grpc.target("orders").over(server.managed)
            scenario("checkout") { exec(orders.call(Orders.placeOrder) { server.place("anvil") }) }
                .at(10.perSecond, over = 200.milliseconds)
                .run(Progress.silent)
        }

    private val row = "orders.v1.Orders/PlaceOrder"

    @Test
    fun `a target saying no is the status it said`() {
        val result = ranAgainst(Status.NOT_FOUND)

        result[row].failedWith(GrpcStatus(Status.Code.NOT_FOUND)) shouldBe 2L
        withClue("and not a row about the exception class that carried it") {
            result[row].failedWith(Threw("io.grpc.StatusRuntimeException")) shouldBe 0L
        }
    }

    @Test
    fun `two statuses are two rows, which is the whole point of keeping them apart`() {
        val result = ranAgainst(Status.RESOURCE_EXHAUSTED)

        result[row].failedWith(GrpcStatus(Status.Code.RESOURCE_EXHAUSTED)) shouldBe 2L
        result[row].failedWith(GrpcStatus(Status.Code.NOT_FOUND)) shouldBe 0L
    }

    @Test
    fun `a socket giving up is UNAVAILABLE, which is where a status model beats an exception class`() {
        val nowhere = ServerSocket(0).use { it.localPort }
        val channel = ManagedChannelBuilder.forTarget("localhost:$nowhere").usePlaintext().build()

        val result = try {
            val orders = grpc.target("localhost:$nowhere").over(channel)
            scenario("checkout") {
                exec(
                    orders.call(Orders.placeOrder) {
                        io.grpc.stub.ClientCalls.blockingUnaryCall(
                            channel,
                            Orders.placeOrder,
                            io.grpc.CallOptions.DEFAULT,
                            "anvil",
                        )
                    },
                )
            }.at(4.perSecond, over = 250.milliseconds).run(Progress.silent)
        } finally {
            channel.shutdownNow()
        }

        withClue("a refused connection arrives as a status, not as a ConnectException") {
            result[row].failedWith(GrpcStatus(Status.Code.UNAVAILABLE)) shouldBe result[row].failed.count
        }
        result[row].ok.count shouldBe 0L
    }

    @Test
    fun `a target that was reachable and too slow is TimedOut, under the one name every module uses`() {
        val result = ranAgainst(Status.DEADLINE_EXCEEDED)

        withClue("one answer to 'how many timed out' across HTTP, WebSocket and gRPC") {
            result[row].failedWith(TimedOut) shouldBe 2L
            result[row].failedWith(GrpcStatus(Status.Code.DEADLINE_EXCEEDED)) shouldBe 0L
        }
    }

    @Test
    fun `a marshaller that cannot serialise its own argument is the caller's bug, not the target's day`() {
        val result = InProcess().use { server ->
            val orders = grpc.target("orders").over(server.managed)
            scenario("checkout") {
                exec(orders.call(Orders.placeOrder) { throw IllegalStateException("no marshaller") })
            }.at(10.perSecond, over = 200.milliseconds).run(Progress.silent)
        }

        withClue("it escapes to the engine, which names the class") {
            result[row].failedWith(Threw("java.lang.IllegalStateException")) shouldBe 2L
        }
    }
}
