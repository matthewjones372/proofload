package io.github.matthewjones372.kestrel.plan.kafka

import io.github.matthewjones372.kestrel.Progress
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.engine.run
import io.github.matthewjones372.kestrel.kafka.FakeBroker
import io.github.matthewjones372.kestrel.kafka.kafka
import io.github.matthewjones372.kestrel.kafka.produce
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.plan.Declaration
import io.github.matthewjones372.kestrel.plan.DeclaredLoad
import io.github.matthewjones372.kestrel.plan.DeclaredStep
import io.github.matthewjones372.kestrel.plan.asSimulation
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.step
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * A declared produce step and the Kotlin somebody would have written have to
 * measure the same thing. Judged against a socket a real `KafkaProducer`
 * connects to, because what a plan buys is the producer's own accumulator and
 * no mock has one.
 */
class LoweringTest {

    private val placeOrder = step("place order")

    private val body = """{"cart":"1 anvil"}"""

    private fun declared(brokers: String) = Declaration(
        version = Declaration.VERSION,
        brokers = brokers,
        scenario = "orders",
        steps = listOf(DeclaredStep.Produce(name = "place order", topic = FakeBroker.TOPIC, body = body)),
        load = DeclaredLoad.Constant(20.perSecond, 250.milliseconds),
    )

    @Test
    fun `a declared produce step sends what the equivalent Kotlin sends`() {
        FakeBroker().use { fromPlan ->
            FakeBroker().use { fromKotlin ->
                declared(fromPlan.bootstrap).asSimulation(listOf(KafkaSteps()))
                    .arms.single().scenario
                    .at(20.perSecond, over = 250.milliseconds)
                    .run(Progress.silent)

                val written = scenario("orders") {
                    produce(
                        placeOrder,
                        kafka.brokers(fromKotlin.bootstrap).topic(FakeBroker.TOPIC).value { body.toByteArray() },
                    )
                }
                written.at(20.perSecond, over = 250.milliseconds).run(Progress.silent)

                withClue("a plan that produced a different number of records is not the same run") {
                    fromPlan.recordsProduced shouldBe fromKotlin.recordsProduced
                }
            }
        }
    }

    @Test
    fun `a produce step is a row under the name the plan gave it`() {
        FakeBroker().use { broker ->
            val result = declared(broker.bootstrap).asSimulation(listOf(KafkaSteps()))
                .arms.single().scenario
                .at(20.perSecond, over = 250.milliseconds)
                .run(Progress.silent)

            withClue("a step folded into another is a round trip reported as a publish") {
                result.steps.keys shouldBe setOf("place order")
            }
        }
    }

    @Test
    fun `a request step is left to whatever lowers one`() {
        val http = DeclaredStep.Request(name = "browse", method = "GET", path = "/products")

        withClue("a lowering that answered for every step would take the ones it does not know") {
            KafkaSteps().lower(http, declared("localhost:9092")) shouldBe null
        }
    }

    @Test
    fun `a plan whose settings name acks reaches the producer with them`() {
        FakeBroker().use { broker ->
            val withAcks = declared(broker.bootstrap).copy(
                steps = listOf(
                    DeclaredStep.Produce(
                        name = "place order",
                        topic = FakeBroker.TOPIC,
                        body = body,
                        settings = mapOf("acks" to "0"),
                    ),
                ),
            )

            val result = withAcks.asSimulation(listOf(KafkaSteps()))
                .arms.single().scenario
                .at(20.perSecond, over = 250.milliseconds)
                .run(Progress.silent)

            withClue("a setting nobody passed on is a producer configured differently from the file") {
                result["place order"].failed.count shouldBe 0L
            }
        }
    }

    @Test
    fun `a produce step whose plan names no brokers is refused by the plan, not by the client`() {
        val thrown = shouldThrow<IllegalArgumentException> {
            declared("localhost:9092").copy(brokers = null).asSimulation(listOf(KafkaSteps()))
        }

        thrown.message.orEmpty() shouldContain "brokers"
    }
}
