package io.github.matthewjones372.kestrel.contract

import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.plan.DeclaredLoad
import io.github.matthewjones372.kestrel.plan.asSimulation
import io.github.matthewjones372.pelican.Method
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.pathParam
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * A contract already says what a load test would otherwise say a second time.
 * These are real endpoint values, not a stand-in for one: what the generator
 * has to read is what Pelican actually holds.
 */
class EndpointsTest {

    private data class Order(val id: Long, val cart: String)

    private val orderId = pathParam<Long>("id", description = "Which order")

    private val getOrder = endpoint(orderId) {
        get("orders" / orderId)
        operationId = "getOrder"
        json<Order>()
    }

    private val listOrders = endpoint {
        get("orders")
        operationId = "listOrders"
        json<List<Order>>()
    }

    private val placeOrder = endpoint {
        post("orders")
        operationId = "placeOrder"
        json<Order>()
    }

    @Test
    fun `a step per endpoint, named as the contract names the operation`() {
        val plan = planFrom(listOf(getOrder, listOrders), baseUrl = "https://orders.internal")

        withClue("the operation id is what the service's own document calls the row") {
            plan.steps.map { it.name } shouldContainExactly listOf("getOrder", "listOrders")
        }
    }

    @Test
    fun `a path parameter is one step, not one step per value`() {
        val plan = planFrom(listOf(getOrder), baseUrl = "https://orders.internal")

        withClue("ten ids would otherwise be ten rows describing one endpoint") {
            plan.steps.single().path shouldContain "{id}"
        }
    }

    @Test
    fun `nothing that writes is generated unless it was asked for`() {
        val read = planFrom(listOf(getOrder, placeOrder), baseUrl = "https://orders.internal")

        withClue("a generated DELETE loop against staging is somebody's evening") {
            read.steps.map { it.name } shouldContainExactly listOf("getOrder")
        }
    }

    @Test
    fun `asking for a method the contract does not serve says which it does`() {
        val thrown = shouldThrow<IllegalArgumentException> {
            planFrom(listOf(getOrder), baseUrl = "https://orders.internal", methods = setOf(Method.DELETE))
        }

        thrown.message.orEmpty() shouldContain "GET"
    }

    @Test
    fun `the generated load is a smoke, not an event`() {
        val plan = planFrom(listOf(getOrder), baseUrl = "https://orders.internal")

        plan.load shouldBe DeclaredLoad.Constant(1.perSecond, 10.seconds)
    }

    @Test
    fun `every step carries a goal, so the plan does not pass while asserting nothing`() {
        val plan = planFrom(listOf(getOrder, listOrders), baseUrl = "https://orders.internal")

        plan.goals.map { it.step } shouldContainExactly listOf("getOrder", "listOrders")
    }

    @Test
    fun `what it generates is a plan the reader already accepts`() {
        val simulation = planFrom(listOf(getOrder, listOrders), baseUrl = "https://orders.internal").asSimulation()

        simulation.arms.single().scenario.steps.size shouldBe 2
    }
}
