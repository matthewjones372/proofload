package io.github.matthewjones372.proofload.grpc

import io.github.matthewjones372.proofload.Progress
import io.github.matthewjones372.proofload.RunResult
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.engine.run
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.scenario
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * A gRPC call is a step like any other. What this module adds is that the row
 * is named by the method rather than by whatever string somebody typed.
 */
class GrpcStepTest {

    /**
     * One server and one channel for the whole run, which is the arrangement
     * this module is built around: a channel per user would measure connection
     * setup rather than the target.
     */
    private fun ran(scenarioOf: (Grpc, InProcess) -> io.github.matthewjones372.proofload.Scenario): RunResult =
        InProcess().use { server ->
            val orders = grpc.target("orders.v1.Orders").over(server.managed)
            scenarioOf(orders, server).at(20.perSecond, over = 250.milliseconds).run(Progress.silent)
        }

    @Test
    fun `a unary call is one row, named for the method`() {
        val result = ran { orders, server ->
            scenario("checkout") { exec(orders.call(Orders.placeOrder) { server.place("anvil") }) }
        }

        result.steps.keys shouldBe setOf("orders.v1.Orders/PlaceOrder")
        withClue("every departure made one call") { result["orders.v1.Orders/PlaceOrder"].ok.count shouldBe 5L }
    }

    @Test
    fun `the answer comes back for a step that reads it`() {
        val seen = java.util.concurrent.ConcurrentLinkedQueue<String>()

        InProcess().use { server ->
            val orders = grpc.target("orders").over(server.managed)
            val checkout = scenario("checkout") {
                exec("place") { send(orders.call(Orders.placeOrder) { server.place("anvil") })?.let { seen += it } }
            }
            checkout.at(10.perSecond, over = 200.milliseconds).run(Progress.silent)
        }

        seen.toList().distinct() shouldBe listOf("filled anvil")
    }

    @Test
    fun `a streaming descriptor is refused where it is written, not at the hundredth message`() {
        val why = shouldThrow<IllegalArgumentException> {
            grpc.target("orders").call(Orders.watchFills) { "never" }
        }.message.orEmpty()

        withClue(why) {
            why shouldContain "orders.v1.Orders/WatchFills"
            why shouldContain "SERVER_STREAMING"
        }
    }

    @Test
    fun `two call sites of one method are one row`() {
        val result = ran { orders, server ->
            scenario("checkout") {
                exec(orders.call(Orders.placeOrder) { server.place("one") })
                exec(orders.call(Orders.placeOrder) { server.place("another") })
            }
        }

        withClue("named off the descriptor, so a rename moves both and neither invents a row") {
            result.steps.keys shouldBe setOf("orders.v1.Orders/PlaceOrder")
            result["orders.v1.Orders/PlaceOrder"].count shouldBe 10L
        }
    }

    @Test
    fun `a target with no channel of its own says which call it could not make`() {
        val why = shouldThrow<IllegalArgumentException> { grpc.channel }.message.orEmpty()

        withClue(why) { why shouldContain "no target" }
    }
}
