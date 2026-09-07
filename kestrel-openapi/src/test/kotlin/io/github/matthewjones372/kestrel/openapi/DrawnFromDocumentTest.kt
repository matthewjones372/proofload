package io.github.matthewjones372.kestrel.openapi

import io.github.matthewjones372.kestrel.plan.DeclaredDraw
import io.github.matthewjones372.kestrel.plan.asSimulation
import io.github.matthewjones372.kestrel.plan.asYaml
import io.github.matthewjones372.kestrel.plan.requests
import io.github.matthewjones372.kestrel.sessionKey
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * A generated plan is the artefact most people read first. Substituting one
 * legal id taught every reader to send the same request ten thousand times,
 * which is the shape 0096 argues against — and the contract already says what
 * the space is.
 */
class DrawnFromDocumentTest {

    private val document = """
        openapi: 3.1.0
        servers: [{url: "http://shop.internal"}]
        paths:
          /products/{sku}:
            get:
              operationId: getProduct
              parameters:
                - name: sku
                  in: path
                  required: true
                  schema: {type: integer, minimum: 1, maximum: 500}
              responses:
                "200": {description: one product}
          /regions/{region}:
            get:
              operationId: getRegion
              parameters:
                - name: region
                  in: path
                  required: true
                  schema: {type: string, enum: [emea, apac, amer]}
              responses:
                "200": {description: a region}
    """.trimIndent()

    private val plan = planFromDocument(document)

    @Test
    fun `a bounded number becomes a draw over the range the contract states`() {
        withClue(plan.asYaml()) {
            plan.draw["sku"] shouldBe DeclaredDraw.Uniform(keys = 500, from = 1)
        }
    }

    @Test
    fun `the template survives, so the draw has something to fill`() {
        withClue("a substituted id cannot be drawn for later; the brace is the whole point") {
            plan.requests().single { it.name == "getProduct" }.path shouldBe "/products/{sku}"
        }
    }

    @Test
    fun `an enum becomes every value it lists, not the first one`() {
        withClue("taking the first enum value sends one region and calls it a load test") {
            plan.draw["region"] shouldBe DeclaredDraw.OneOf(listOf("emea", "apac", "amer"))
        }
    }

    @Test
    fun `two users of the generated plan send two different paths`() {
        val sku = sessionKey<String>("sku")
        val feeder = plan.asSimulation().arms.single().feeder

        val drawn = (0L until 200L).mapNotNull { feeder.forUser(it)[sku] }

        withClue("this is the whole point of the change") {
            drawn.distinct().size shouldBeGreaterThan 1
            drawn.map { it.toLong() }.filter { it !in 1..500 } shouldContainExactly emptyList()
        }
    }

    @Test
    fun `a parameter the contract does not bound is still substituted`() {
        val unbounded = planFromDocument(
            """
            openapi: 3.1.0
            servers: [{url: "http://shop.internal"}]
            paths:
              /orders/{ref}:
                get:
                  operationId: getOrder
                  parameters:
                    - name: ref
                      in: path
                      required: true
                      schema: {type: string}
                  responses:
                    "200": {description: ok}
            """.trimIndent(),
        )

        withClue("inventing a range the contract never stated would be inventing a cardinality") {
            unbounded.draw shouldBe emptyMap()
            unbounded.requests().single().path shouldContain "/orders/"
            unbounded.requests().single().path.contains('{') shouldBe false
        }
    }
}
