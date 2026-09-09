package io.github.matthewjones372.proofload.plan

import io.github.matthewjones372.proofload.Session
import io.github.matthewjones372.proofload.sessionKey
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * A generated plan is the artefact most people read first, and until now it
 * substituted one id and taught every reader to hammer one row. What a drawn
 * plan has to get right is that two users send two different things, and that
 * the run says what they were drawn from.
 */
class DrawingTest {

    private val plan = """
        proofload:  plan/1
        baseUrl:  https://orders.internal
        scenario: checkout
        draw:
          sku:      {uniform: 500}
          customer: {zipf: {keys: 1000000, skew: 1.1}}
          basket:   {oneOf: [anvil, rocket, birdseed]}
        steps:
          - name: open product
            get: /products/{sku}
        load:
          rate: 50/s
          over: 1m
    """.trimIndent()

    private fun sessionFor(user: Long, of: Declaration = readPlan(plan)): Session =
        of.asSimulation().arms.single().feeder.forUser(user)

    @Test
    fun `two users are given two different values`() {
        val sku = sessionKey<String>("sku")

        val drawn = (0L until 200L).map { sessionFor(it)[sku] }

        withClue("one id ten thousand times is a measurement of one row and one cache line") {
            drawn.distinct().size shouldBeGreaterThan 1
        }
    }

    @Test
    fun `a drawn value reaches the session as a string, because that is what a path reads`() {
        withClue("interpolation reads sessionKey<String>; a Long under that name is a throw, not a failure") {
            sessionFor(7L)[sessionKey<String>("sku")].shouldNotBe(null)
        }
    }

    @Test
    fun `the same user draws the same value, run after run`() {
        withClue("a failure at user 8,412 has to be re-derivable") {
            sessionFor(8_412L)[sessionKey<String>("sku")] shouldBe sessionFor(8_412L)[sessionKey<String>("sku")]
        }
    }

    @Test
    fun `two keys of one plan do not draw the same values`() {
        val sku = sessionKey<String>("sku")
        val basket = sessionKey<String>("basket")

        val pairs = (0L until 100L).map { sessionFor(it)[sku] to sessionFor(it)[basket] }

        withClue("seeded alike, customer 41 would always buy item 41 and nobody would see it") {
            pairs.map { it.first }.distinct().size shouldBeGreaterThan 1
            pairs.map { it.second }.distinct().size shouldBeGreaterThan 1
        }
    }

    @Test
    fun `oneOf draws only what it was given`() {
        val basket = sessionKey<String>("basket")

        val drawn = (0L until 300L).mapNotNull { sessionFor(it)[basket] }.distinct().sorted()

        drawn shouldContainExactly listOf("anvil", "birdseed", "rocket")
    }

    @Test
    fun `the run says what its data was drawn from`() {
        val shapes = readPlan(plan).asSimulation().arms.single().drawn.map { it.description }

        withClue("a comparison refuses two runs drawn differently, and cannot without this") {
            shapes.size shouldBe 3
            shapes.joinToString() shouldContain "zipf"
            shapes.joinToString() shouldContain "uniform"
        }
    }

    @Test
    fun `a plan that draws round-trips through the writer`() {
        val read = readPlan(plan)

        withClue(read.asYaml()) { readPlan(read.asYaml()) shouldBe read }
    }

    @Test
    fun `a plan that draws nothing has no shapes and no feeder of its own`() {
        val plain = readPlan(plan.replaceAfter("scenario: checkout", "").trimEnd() + "\n" + PLAIN)

        plain.asSimulation().arms.single().drawn shouldBe emptyList()
    }

    private companion object {
        val PLAIN = """
            steps:
              - name: browse
                get: /products
            load:
              rate: 50/s
              over: 1m
        """.trimIndent()
    }
}
