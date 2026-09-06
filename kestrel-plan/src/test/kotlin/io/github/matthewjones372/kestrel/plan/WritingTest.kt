package io.github.matthewjones372.kestrel.plan

import io.github.matthewjones372.kestrel.perSecond
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A written plan has to be a plan this reads. Anything else is a generator
 * whose output nobody can run.
 */
class WritingTest {

    private val plan = Declaration(
        version = Declaration.VERSION,
        baseUrl = "https://orders.internal",
        scenario = "checkout",
        steps = listOf(
            DeclaredStep.Request(name = "browse", method = "GET", path = "/products"),
            DeclaredStep.Request(
                name = "place order",
                method = "POST",
                path = "/orders",
                headers = mapOf("content-type" to "application/json"),
                body = """{"cart":"1 anvil"}""",
                expecting = 201,
                declared = listOf(409),
                pauseAfter = 2.seconds,
            ),
        ),
        load = DeclaredLoad.Constant(50.perSecond, 1.minutes),
        goals = listOf(DeclaredGoal.Percentile("place order", "p99", 200.milliseconds)),
    )

    @Test
    fun `what it writes is what it reads`() {
        withClue(plan.asYaml()) {
            readPlan(plan.asYaml()) shouldBe plan
        }
    }

    @Test
    fun `a staged load round-trips too`() {
        val staged = plan.copy(
            load = DeclaredLoad.Staged(
                listOf(
                    DeclaredLoad.Constant(10.perSecond, 10.seconds),
                    DeclaredLoad.Constant(200.perSecond, 20.seconds),
                ),
            ),
        )

        withClue(staged.asYaml()) {
            readPlan(staged.asYaml()) shouldBe staged
        }
    }

    @Test
    fun `a body with punctuation in it survives being written`() {
        val awkward = plan.copy(
            steps = listOf(DeclaredStep.Request("odd", "POST", "/x", body = """{"a": "b: c", "d": "it's"}""")),
            goals = emptyList(),
        )

        readPlan(awkward.asYaml()) shouldBe awkward
    }

    @Test
    fun `a produce step is written in Kafka's own words`() {
        val topics = Declaration(
            version = Declaration.VERSION,
            brokers = "localhost:9092",
            scenario = "orders",
            steps = listOf(
                DeclaredStep.Produce(
                    name = "place order",
                    topic = "orders",
                    body = """{"cart":"1 anvil"}""",
                    key = "anvil-1",
                    settings = mapOf("acks" to "all"),
                ),
            ),
            load = DeclaredLoad.Constant(500.perSecond, 1.minutes),
        )

        withClue(topics.asYaml()) {
            topics.asYaml() shouldContain "brokers:  localhost:9092"
            topics.asYaml() shouldContain "    produce: orders"
            topics.asYaml() shouldContain "    key: anvil-1"
            topics.asYaml() shouldContain "      acks: all"
            withClue("a plan of nothing but topics has no baseUrl to write") {
                topics.asYaml() shouldNotContain "baseUrl"
            }
        }
    }

    @Test
    fun `it reads as a file a person would edit`() {
        withClue(plan.asYaml()) {
            plan.asYaml() shouldContain "kestrel:  plan/1"
            plan.asYaml() shouldContain "  - name: browse"
            plan.asYaml() shouldContain "    get: /products"
        }
    }
}
