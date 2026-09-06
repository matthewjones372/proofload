package io.github.matthewjones372.kestrel.plan

import io.github.matthewjones372.kestrel.perSecond
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * The errors are the feature. A caller iterating against a parser rather than a
 * compiler only wins if the parser says what to change.
 */
class ReadingTest {

    private val plan = """
        # what checkout does under load
        kestrel:  plan/1
        baseUrl:  https://orders.internal
        scenario: checkout
        steps:
          - name: browse
            get:  /products
          - name: place order
            post: /orders
            body: '{"cart":"1 anvil"}'
            expecting: 201
            headers:
              content-type: application/json
        load:
          rate: 50/s
          over: 1m
        goals:
          - step: place order
            p99:  200ms
    """.trimIndent()

    @Test
    fun `a plan reads into the value the DSL would build`() {
        val read = readPlan(plan)

        read.version shouldBe "plan/1"
        read.baseUrl shouldBe "https://orders.internal"
        read.steps.map { it.name } shouldBe listOf("browse", "place order")
        read.steps[1].expecting shouldBe 201
        read.steps[1].headers shouldBe mapOf("content-type" to "application/json")
        read.load shouldBe DeclaredLoad.Constant(50.perSecond, 1.minutes)
        read.goals shouldBe listOf(DeclaredGoal.Percentile("place order", "p99", 200.milliseconds))
    }

    @Test
    fun `the same plan written as json reads the same way`() {
        val json = """
            {"kestrel":"plan/1","baseUrl":"https://orders.internal","scenario":"checkout",
             "steps":[{"name":"browse","get":"/products"}],
             "load":{"rate":"50/s","over":"1m"}}
        """.trimIndent()

        withClue("yaml 1.2 is a superset of json, which is why there is one parser and not two") {
            readPlan(json).steps.single().name shouldBe "browse"
        }
    }

    @Test
    fun `a key nobody declared names itself, its line, and what was allowed`() {
        val thrown = shouldThrow<IllegalArgumentException> {
            readPlan(plan.replace("scenario: checkout", "scenarios: checkout"))
        }

        withClue(thrown.message.orEmpty()) {
            thrown.message.orEmpty() shouldContain "line 4"
            thrown.message.orEmpty() shouldContain "scenarios"
            thrown.message.orEmpty() shouldContain "`scenario`"
        }
    }

    @Test
    fun `a rate nobody can read names its line`() {
        val thrown = shouldThrow<IllegalArgumentException> { readPlan(plan.replace("rate: 50/s", "rate: quickly")) }

        withClue(thrown.message.orEmpty()) {
            thrown.message.orEmpty() shouldContain "quickly"
            thrown.message.orEmpty() shouldContain "line"
        }
    }

    @Test
    fun `a missing key says which one`() {
        val thrown = shouldThrow<IllegalArgumentException> {
            readPlan(plan.replace("baseUrl:  https://orders.internal\n", ""))
        }

        thrown.message.orEmpty() shouldContain "baseUrl"
    }

    @Test
    fun `a step with no verb says which verbs there are`() {
        val thrown = shouldThrow<IllegalArgumentException> {
            readPlan(plan.replace("get:  /products", "fetch: /products"))
        }

        thrown.message.orEmpty() shouldContain "fetch"
    }

    @Test
    fun `a read plan lowers without further complaint`() {
        readPlan(plan).asSimulation().arms.single().scenario.steps.size shouldBe 2
    }

    @Test
    fun `a staged load reads as stages in order`() {
        val staged = readPlan(
            plan.replace(
                "  rate: 50/s\n  over: 1m",
                "  stages:\n    - {rate: 10/s, over: 10s}\n    - {rate: 200/s, over: 20s}",
            ),
        )

        staged.load.shouldBeInstanceOf<DeclaredLoad.Staged>().stages.size shouldBe 2
    }
}
