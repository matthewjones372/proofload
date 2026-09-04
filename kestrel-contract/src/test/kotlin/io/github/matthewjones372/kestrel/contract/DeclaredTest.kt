package io.github.matthewjones372.kestrel.contract

import io.github.matthewjones372.kestrel.plan.asKotlin
import io.github.matthewjones372.pelican.IntCodec
import io.github.matthewjones372.pelican.between
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.pathParam
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * The statuses a contract writes down are the ones a run should not read as
 * defects. Every other load tool has to be told them by hand, per step.
 */
class DeclaredTest {

    private data class Order(val id: Long)

    private data class NoSuchOrder(val id: Long, val message: String)

    private val orderId = pathParam("id", IntCodec.between(1, 100))

    private val orderMissing = errorJson<NoSuchOrder>(404, "No order with that id")

    private val getOrder = endpoint(orderId) {
        get("orders" / orderId)
        operationId = "getOrder"
        json<Order>() orFail orderMissing
    }

    private val listOrders = endpoint {
        get("orders")
        operationId = "listOrders"
        json<Order>()
    }

    @Test
    fun `a declared failure travels into the plan as one`() {
        val plan = planFrom(listOf(getOrder), baseUrl = "https://orders.internal")

        withClue("orFail put a 404 in the endpoint's own type; nothing here guessed it") {
            plan.steps.single().declared shouldContainExactly listOf(404)
        }
    }

    @Test
    fun `an endpoint that declares nothing declares nothing`() {
        planFrom(listOf(listOrders), baseUrl = "https://orders.internal").steps.single().declared.shouldBeEmpty()
    }

    @Test
    fun `the emitted Kotlin carries the declaration too`() {
        val source = planFrom(listOf(getOrder), baseUrl = "https://orders.internal")
            .asKotlin(packageName = "load", from = "orders.yaml")

        withClue("graduating to the DSL must not lose what the contract knew") {
            source shouldContain ".declaring(404)"
        }
    }
}
