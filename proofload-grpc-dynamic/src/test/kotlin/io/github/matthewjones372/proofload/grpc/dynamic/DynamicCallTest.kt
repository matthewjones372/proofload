package io.github.matthewjones372.proofload.grpc.dynamic

import io.github.matthewjones372.proofload.Progress
import io.github.matthewjones372.proofload.RunResult
import io.github.matthewjones372.proofload.TimedOut
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.engine.run
import io.github.matthewjones372.proofload.grpc.GrpcStatus
import io.github.matthewjones372.proofload.grpc.grpc
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.scenario
import io.grpc.Status
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * A call made from a name and a JSON body, with no generated stub on either
 * side of it. What the step has to get right is the row it reports under and
 * the difference between a status the service documents and one it does not.
 */
class DynamicCallTest {

    private val schema = descriptorSet(Shop.descriptorSet)

    private val body = """{"cart": "1 anvil"}"""

    private fun ran(answers: (String) -> Status, declaring: List<Status.Code> = emptyList()): RunResult =
        ShopServer(answers).use { server ->
            val service = grpc.over(server.channel())
            val call = service.call(schema, "shop.Orders/PlaceOrder", body)
                .declaring(*declaring.toTypedArray())

            scenario("orders") { exec(call) }
                .at(20.perSecond, over = 250.milliseconds)
                .run(Progress.silent)
        }

    @Test
    fun `the row is the method, as the wire names it`() {
        val result = ran({ Status.OK })

        withClue("two call sites of one method are one row, and a plan writes this name") {
            result.steps.keys shouldBe setOf("shop.Orders/PlaceOrder")
        }
    }

    @Test
    fun `an answer the schema fits is a success`() {
        val result = ran({ Status.OK })

        result["shop.Orders/PlaceOrder"].failed.count shouldBe 0L
        result["shop.Orders/PlaceOrder"].count shouldBe result["shop.Orders/PlaceOrder"].count
    }

    @Test
    fun `a declared NOT_FOUND reports as declared`() {
        val result = ran({ Status.NOT_FOUND }, declaring = listOf(Status.Code.NOT_FOUND))

        withClue("a service answering as written is not a service doing something nobody wrote down") {
            result["shop.Orders/PlaceOrder"].failed.reasons.keys shouldBe
                setOf(DeclaredGrpcStatus(Status.Code.NOT_FOUND))
        }
    }

    @Test
    fun `an undeclared INTERNAL does not`() {
        val result = ran({ Status.INTERNAL }, declaring = listOf(Status.Code.NOT_FOUND))

        result["shop.Orders/PlaceOrder"].failed.reasons.keys shouldBe setOf(GrpcStatus(Status.Code.INTERNAL))
    }

    @Test
    fun `a status nobody declared is named rather than thrown`() {
        val result = ran({ Status.UNAVAILABLE })

        withClue("UNAVAILABLE and NOT_FOUND are two findings; one Threw row covering both answers neither") {
            result["shop.Orders/PlaceOrder"].failed.reasons.keys shouldBe setOf(GrpcStatus(Status.Code.UNAVAILABLE))
        }
    }

    @Test
    fun `a deadline is core's own timeout, whichever protocol said so`() {
        val result = ran({ Status.DEADLINE_EXCEEDED }, declaring = listOf(Status.Code.DEADLINE_EXCEEDED))

        withClue("`how many timed out` has one answer across HTTP, WebSocket and gRPC") {
            result["shop.Orders/PlaceOrder"].failed.reasons.keys shouldBe setOf(TimedOut)
        }
    }

    @Test
    fun `a method whose success is a status counts an OK as the failure it is`() {
        ShopServer({ Status.OK }).use { server ->
            val call = grpc.over(server.channel())
                .call(schema, "shop.Orders/PlaceOrder", body)
                .expecting(Status.Code.NOT_FOUND)

            val result = scenario("orders") { exec(call) }
                .at(20.perSecond, over = 250.milliseconds)
                .run(Progress.silent)

            result["shop.Orders/PlaceOrder"].failed.reasons.keys shouldBe setOf(GrpcStatus(Status.Code.OK))
        }
    }

    @Test
    fun `a body that does not fit the schema is refused while the scenario is built`() {
        val thrown = shouldThrow<IllegalArgumentException> {
            grpc.call(schema, "shop.Orders/PlaceOrder", """{"cartt": "typo"}""")
        }

        withClue("the only moment a plan can still be corrected is before anything departs") {
            thrown.message.orEmpty() shouldContain "cartt"
        }
    }

    @Test
    fun `nothing is sent to work out that a body is wrong`() {
        ShopServer({ Status.OK }).use { server ->
            val service = grpc.over(server.channel())

            shouldThrow<IllegalArgumentException> { service.call(schema, "shop.Orders/PlaceOrder", """{"x": 1}""") }

            withClue("a schema refusal that opened a connection would be a fence that leaked") {
                server.calls shouldBe 0
            }
        }
    }
}
