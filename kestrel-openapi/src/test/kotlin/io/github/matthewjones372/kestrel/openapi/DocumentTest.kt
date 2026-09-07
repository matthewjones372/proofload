package io.github.matthewjones372.kestrel.openapi

import io.github.matthewjones372.kestrel.plan.DeclaredDraw
import io.github.matthewjones372.kestrel.plan.asSimulation
import io.github.matthewjones372.kestrel.plan.asYaml
import io.github.matthewjones372.kestrel.plan.requests
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContainAnyOf
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * The half of 0091 that does not need the service to be built from Pelican
 * values: anybody who publishes an OpenAPI document already wrote down what a
 * load test would otherwise say a second time.
 */
class DocumentTest {

    private val document = """
        openapi: 3.1.0
        info: {title: Orders, version: "1.0.0"}
        servers:
          - url: https://orders.internal/
        paths:
          /orders:
            get:
              operationId: listOrders
              responses:
                "200": {description: every order}
          /orders/{id}:
            get:
              operationId: getOrder
              parameters:
                - name: id
                  in: path
                  required: true
                  schema: {type: integer, minimum: 1, maximum: 100}
              responses:
                "200": {description: one order}
                "404": {description: no such order}
            delete:
              operationId: deleteOrder
              responses:
                "204": {description: gone}
    """.trimIndent()

    @Test
    fun `a document becomes a plan the reader already accepts`() {
        val plan = planFromDocument(document)

        plan.baseUrl shouldBe "https://orders.internal"
        plan.steps.map { it.name } shouldContainExactly listOf("listOrders", "getOrder")
        plan.asSimulation().arms.single().scenario.steps.size shouldBe 2
    }

    @Test
    fun `a path parameter bounded by the schema is drawn over it, not substituted once`() {
        val plan = planFromDocument(document)

        withClue(plan.asYaml()) {
            // 0104: the brace stays so the session can fill it per user. Before
            // that this asserted one substituted id, which is the measurement
            // of one row 0096 argues against.
            plan.requests().single { it.name == "getOrder" }.path shouldBe "/orders/{id}"
            plan.draw["id"] shouldBe DeclaredDraw.Uniform(keys = 100, from = 1)
        }
    }

    @Test
    fun `a declared response is declared rather than counted as a defect`() {
        val step = planFromDocument(document).requests().single { it.name == "getOrder" }

        withClue("the document says this endpoint answers 404; a run should not read that as a defect") {
            step.expecting shouldBe 200
            step.declared shouldContainExactly listOf(404)
        }
    }

    @Test
    fun `nothing that writes is generated unless it was asked for`() {
        planFromDocument(document).steps.map { it.name } shouldNotContainAnyOf listOf("deleteOrder")
    }

    @Test
    fun `a document naming no server says to pass one`() {
        val serverless = document.lines().filterNot { it.contains("orders.internal") || it.contains("servers:") }
            .joinToString("\n")

        shouldThrow<IllegalArgumentException> { planFromDocument(serverless) }
            .message.orEmpty() shouldContain "baseUrl"
    }

    @Test
    fun `the same document as json reads the same way`() {
        val json = """
            {"openapi":"3.1.0","servers":[{"url":"https://orders.internal"}],
             "paths":{"/orders":{"get":{"operationId":"listOrders","responses":{"200":{"description":"ok"}}}}}}
        """.trimIndent()

        withClue("yaml 1.2 is a superset of json, which is why there is one parser") {
            planFromDocument(json).steps.single().name shouldBe "listOrders"
        }
    }

    @Test
    fun `a parameter behind a ref is followed`() {
        val referenced = """
            openapi: 3.1.0
            servers: [{url: "https://orders.internal"}]
            components:
              parameters:
                OrderId: {name: id, in: path, required: true, schema: {type: integer, minimum: 5, maximum: 5}}
            paths:
              /orders/{id}:
                get:
                  operationId: getOrder
                  parameters:
                    - ${'$'}ref: '#/components/parameters/OrderId'
                  responses:
                    "200": {description: one order}
        """.trimIndent()

        val plan = planFromDocument(referenced)

        withClue("the ref is followed when its facets reach the draw, which only a resolved schema has") {
            plan.requests().single().path shouldBe "/orders/{id}"
            plan.draw["id"] shouldBe DeclaredDraw.Uniform(keys = 1, from = 5)
        }
    }

    /**
     * Found through the MCP server, where this was the call that killed it: a
     * parse failure is the parser's own type, so it went past the
     * `IllegalArgumentException` every caller catches.
     */
    @Test
    fun `something that is not yaml at all is refused, not thrown past the caller`() {
        val thrown = shouldThrow<IllegalArgumentException> { planFromDocument("not: an: openapi") }

        withClue(thrown.message.orEmpty()) { thrown.message.orEmpty() shouldContain "line 1" }
    }
}
