package io.github.matthewjones372.kestrel.grpc

import io.github.matthewjones372.kestrel.Progress
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.engine.run
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.scenario
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * Read off the server rather than off what the client built: what a target's
 * tracing backend joins on is what reached the wire.
 */
class TracedTest {

    private val wellFormed = Regex("00-[0-9a-f]{32}-[0-9a-f]{16}-00")

    private fun called(tracing: Boolean): Heard = InProcess().use { server ->
        val orders = grpc.target("orders").over(server.managed).let { if (tracing) it.traced() else it }
        scenario("checkout") { exec(orders.call(Orders.placeOrder) { server.place("anvil", on = orders.channel) }) }
            .at(20.perSecond, over = 250.milliseconds)
            .run(Progress.silent)
        server.heard
    }

    @Test
    fun `every call carries a well-formed traceparent, and the load marker beside it`() {
        val heard = called(tracing = true)

        withClue(heard.traceparents.toString()) {
            heard.traceparents.size shouldBe 5
            heard.traceparents.all { it != null && wellFormed.matches(it) } shouldBe true
        }
        withClue("so a shared target can tell a run's traffic from its users' before it autoscales") {
            heard.baggage.distinct() shouldBe listOf("synthetic=true")
        }
    }

    @Test
    fun `the ids differ per call, so two of them are two requests in a backend`() {
        val heard = called(tracing = true)

        withClue(heard.traceparents.toString()) {
            heard.traceparents.distinct().size shouldBe heard.traceparents.size
        }
    }

    @Test
    fun `the trace id is never the all-zero one the format forbids`() {
        val ids = called(tracing = true).traceparents.map { it!!.substring(3, 35) }

        ids.filter { it.all { digit -> digit == '0' } }.shouldBeEmpty()
    }

    @Test
    fun `an untraced target sends neither header`() {
        val heard = called(tracing = false)

        withClue("a request that carries a header the scenario did not write is a different request") {
            heard.traceparents.filterNotNull().shouldBeEmpty()
            heard.baggage.filterNotNull().shouldBeEmpty()
        }
    }

    @Test
    fun `the id the call sent is the one beside the percentile it is about`() {
        val result = InProcess().use { server ->
            val orders = grpc.target("orders").over(server.managed).traced()
            scenario("checkout") { exec(orders.call(Orders.placeOrder) { server.place("anvil", on = orders.channel) }) }
                .at(20.perSecond, over = 250.milliseconds)
                .run(Progress.silent)
        }

        val exemplar = result["orders.v1.Orders/PlaceOrder"].ok.serviceTime.exemplar(99.0)
        withClue("$exemplar") { exemplar!!.length shouldBe 32 }
    }
}
