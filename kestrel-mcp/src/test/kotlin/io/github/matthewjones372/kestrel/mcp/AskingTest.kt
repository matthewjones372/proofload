package io.github.matthewjones372.kestrel.mcp

import io.github.matthewjones372.kestrel.Allowance
import io.github.matthewjones372.kestrel.plan.readPlan
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * A plan with topics in it is guessing different things from one with paths,
 * and a fence that waved a producer through would be a fence with a hole in
 * the shape of shared infrastructure.
 */
class AskingTest {

    private val plan = """
        kestrel:  plan/1
        brokers:  orders.internal:9092
        scenario: orders
        steps:
          - name: place order
            produce: orders
            key: anvil-1
            body: '{"cart":"1 anvil"}'
        load:
          rate: 500/s
          over: 1m
    """.trimIndent()

    @Test
    fun `a produce step is asked about the cluster it writes to`() {
        val asking = readPlan(plan).questions(Source.Written, smoked = "")

        withClue(asking.joinToString("\n")) {
            asking.single { it.contains("orders.internal") } shouldContain "may write to"
        }
    }

    @Test
    fun `a publish nothing answers is asked which topic carries the answer`() {
        val asking = readPlan(plan).questions(Source.Written, smoked = "")

        withClue(asking.joinToString("\n")) {
            asking.single { it.contains("carries the answer") } shouldContain "place order"
        }
    }

    @Test
    fun `a key every record shares is asked about, because it is one partition`() {
        val asking = readPlan(plan).questions(Source.Written, smoked = "")

        withClue(asking.joinToString("\n")) {
            asking.single { it.contains("one partition") } shouldContain "place order"
        }
    }

    @Test
    fun `an answered produce step is not asked what answers it`() {
        val answered = readPlan(
            """
            kestrel:  plan/1
            brokers:  orders.internal:9092
            scenario: orders
            steps:
              - name: place order
                produce: orders
                body: '{"cart":"1 anvil"}'
              - name: confirmed
                completes: place order
                on: order-confirmations
                by: correlation-id
                within: 30s
            load:
              rate: 500/s
              over: 1m
            """.trimIndent(),
        )

        answered.questions(Source.Written, smoked = "").filter { it.contains("carries the answer") }.shouldBeEmpty()
    }

    @Test
    fun `an allowance that does not name the cluster refuses the benchmark, naming the broker`() {
        val refused = benchmark(mapOf("plan" to plan), Allowance(hosts = listOf("staging.internal")))

        withClue(refused) {
            refused shouldContain "orders.internal"
            withClue("a caller told only that a host is barred cannot see what it was about to send there") {
                refused shouldContain "produce: orders"
            }
        }
    }

    @Test
    fun `an http plan is asked none of this`() {
        val http = readPlan(
            """
            kestrel:  plan/1
            baseUrl:  https://orders.internal
            scenario: checkout
            steps:
              - name: browse
                get: /products
            load:
              rate: 50/s
              over: 1m
            """.trimIndent(),
        )

        http.questions(Source.Written, smoked = "").filter { it.contains("partition") }.shouldBeEmpty()
    }
}
