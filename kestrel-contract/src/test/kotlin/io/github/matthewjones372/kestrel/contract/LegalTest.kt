package io.github.matthewjones372.kestrel.contract

import io.github.matthewjones372.kestrel.plan.requests
import io.github.matthewjones372.pelican.IntCodec
import io.github.matthewjones372.pelican.StringCodec
import io.github.matthewjones372.pelican.between
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.maxLength
import io.github.matthewjones372.pelican.minLength
import io.github.matthewjones372.pelican.pathParam
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * A value inside the constraints exercises the endpoint; one outside them
 * measures the service refusing it. The contract already holds the rule, so
 * nothing here guesses.
 */
class LegalTest {

    private data class Order(val id: Long)

    private val smallId = pathParam("id", IntCodec.between(1, 100))

    private val getOrder = endpoint(smallId) {
        get("orders" / smallId)
        operationId = "getOrder"
        json<Order>()
    }

    @Test
    fun `a generated path carries a value rather than a brace`() {
        val plan = planFrom(listOf(getOrder), baseUrl = "https://orders.internal")

        withClue("a path that kept its braces reads the session, finds nothing, and fails every request") {
            plan.requests().single().path shouldNotContain "{"
            plan.requests().single().path shouldNotContain "}"
        }
    }

    @Test
    fun `a bounded parameter never leaves its bounds, whatever the seed`() {
        val drawn = (0L until 1_000L).map { seed ->
            planFrom(listOf(getOrder), baseUrl = "https://orders.internal", seed = seed)
                .requests().single().path.substringAfterLast('/').toLong()
        }

        withClue("between(1, 100) is both the check that refuses a request and the schema's bounds") {
            drawn.filter { it !in 1..100 } shouldBe emptyList()
        }
        withClue("a generator that always answered 1 would satisfy the bounds and test one row") {
            drawn.distinct().size shouldBeGreaterThan 1
        }
    }

    @Test
    fun `the same contract and seed produce the same plan twice`() {
        val once = planFrom(listOf(getOrder), baseUrl = "https://orders.internal", seed = 7)
        val again = planFrom(listOf(getOrder), baseUrl = "https://orders.internal", seed = 7)

        withClue("a generated file nobody can regenerate identically is a file nobody can review") {
            once shouldBe again
        }
    }

    @Test
    fun `a length-bounded string stays inside its lengths`() {
        val code = pathParam("code", StringCodec.minLength(2).maxLength(4))
        val lookup = endpoint(code) {
            get("codes" / code)
            operationId = "lookup"
            json<Order>()
        }

        val value = planFrom(listOf(lookup), baseUrl = "https://orders.internal").requests().single()
            .path.substringAfterLast('/')

        value.length shouldBe value.length.coerceIn(2, 4)
    }
}
