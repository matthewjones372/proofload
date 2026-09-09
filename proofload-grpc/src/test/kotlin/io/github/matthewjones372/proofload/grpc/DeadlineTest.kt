package io.github.matthewjones372.proofload.grpc

import io.github.matthewjones372.proofload.Progress
import io.github.matthewjones372.proofload.TimedOut
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.engine.run
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.scenario
import io.grpc.CallOptions
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A call with no deadline waits as long as the target likes, which in a load
 * test is a user who never departs again and a percentile that never arrives.
 */
class DeadlineTest {

    private val row = "orders.v1.Orders/PlaceOrder"

    @Test
    fun `a call with no deadline of its own is cancelled at the run's, and recorded as TimedOut`() {
        val result = InProcess(Orders.serving(silent = true)).use { server ->
            val orders = grpc.target("orders").over(server.managed).deadline(200.milliseconds)
            scenario("checkout") { exec(orders.call(Orders.placeOrder) { server.place("anvil", on = orders.channel) }) }
                .at(4.perSecond, over = 250.milliseconds)
                .run(Progress.silent)
        }

        withClue("the same name every module reports a timeout under") {
            result[row].failedWith(TimedOut) shouldBe result[row].count
        }
        result[row].ok.count shouldBe 0L
    }

    @Test
    fun `a caller's own deadline survives, rather than being written over by the run's`() {
        val started = System.nanoTime()
        val result = InProcess(Orders.serving(silent = true)).use { server ->
            // The run's budget is long and the call's is short. If the run's
            // were written over the call's, this would take three seconds.
            val orders = grpc.target("orders").over(server.managed).deadline(3.seconds)
            scenario("checkout") {
                exec(
                    orders.call(Orders.placeOrder) {
                        server.place(
                            "anvil",
                            on = orders.channel,
                            options = CallOptions.DEFAULT.withDeadlineAfter(150, TimeUnit.MILLISECONDS),
                        )
                    },
                )
            }.at(10.perSecond, over = 300.milliseconds).run(Progress.silent)
        }
        val took = (System.nanoTime() - started).let { it / 1_000_000 }.milliseconds

        result[row].failedWith(TimedOut) shouldBe result[row].count
        withClue("took $took, and the run's own budget was three seconds") { took shouldBeLessThan 2.seconds }
    }

    @Test
    fun `a run that declared no budget writes none, so a caller's channel is untouched`() {
        val result = InProcess().use { server ->
            val orders = grpc.target("orders").over(server.managed)
            scenario("checkout") { exec(orders.call(Orders.placeOrder) { server.place("anvil", on = orders.channel) }) }
                .at(10.perSecond, over = 200.milliseconds)
                .run(Progress.silent)
        }

        withClue("nothing here decides a budget nobody asked for") { result[row].ok.count shouldBe 2L }
    }
}
