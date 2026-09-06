package io.github.matthewjones372.kestrel.plan

import io.github.matthewjones372.kestrel.Goal
import io.github.matthewjones372.kestrel.InjectionProfile
import io.github.matthewjones372.kestrel.Step
import io.github.matthewjones372.kestrel.Targeted
import io.github.matthewjones372.kestrel.perSecond
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A plan somebody wrote down is a value, and the value it lowers to is the one
 * the Kotlin DSL builds. If the two ever disagreed there would be two ways to
 * describe a run, which is the thing this module exists not to be.
 */
class DeclarationTest {

    @Test
    fun `a declaration becomes a simulation with a step per declared step`() {
        val simulation = declaration().asSimulation()

        simulation.arms.single().scenario.name shouldBe "checkout"
        simulation.arms.single().scenario.steps.map { (it as Step.Exec).name } shouldBe listOf("browse", "place order")
    }

    @Test
    fun `every lowered step knows the host it sends to`() {
        val steps = declaration().asSimulation().arms.single().scenario.steps

        withClue("a lowered step that is not Targeted is one preview cannot fence") {
            steps.map { (((it as Step.Exec).action) as Targeted).host } shouldBe
                listOf("orders.internal", "orders.internal")
        }
    }

    @Test
    fun `a constant load becomes a constant rate`() {
        val profile = declaration().asSimulation().arms.single().profile

        profile shouldBe InjectionProfile.ConstantRate(50.0, 1.minutes)
    }

    @Test
    fun `stages lower in the order they were written`() {
        val staged = declaration(
            load = DeclaredLoad.Staged(
                listOf(
                    DeclaredLoad.Constant(10.perSecond, 10.seconds),
                    DeclaredLoad.Constant(200.perSecond, 20.seconds),
                ),
            ),
        )

        staged.asSimulation().arms.single().profile shouldBe InjectionProfile.Stages(
            listOf(
                InjectionProfile.ConstantRate(10.0, 10.seconds),
                InjectionProfile.ConstantRate(200.0, 20.seconds),
            ),
        )
    }

    @Test
    fun `a percentile goal lowers to the goal the DSL would build`() {
        val goals = declaration(
            goals = listOf(DeclaredGoal.Percentile(step = "place order", percentile = "p99", under = 200.milliseconds)),
        ).asSimulation().goals

        goals.single().shouldBeInstanceOf<Goal.PercentileUnder>().described shouldBe "place order p99 under 200ms"
    }

    @Test
    fun `a goal naming a step nobody declared is refused before anything is built`() {
        val thrown = shouldThrow<IllegalArgumentException> {
            declaration(goals = listOf(DeclaredGoal.Percentile("checkout", "p99", 200.milliseconds))).asSimulation()
        }

        withClue(thrown.message.orEmpty()) {
            thrown.message.orEmpty() shouldContain "checkout"
            thrown.message.orEmpty() shouldContain "place order"
        }
    }

    @Test
    fun `a version this module does not know is refused`() {
        val thrown = shouldThrow<IllegalArgumentException> { declaration(version = "plan/2").asSimulation() }

        thrown.message.orEmpty() shouldContain "plan/2"
    }

    @Test
    fun `a declaration with no steps is refused`() {
        shouldThrow<IllegalArgumentException> { declaration(steps = emptyList()).asSimulation() }
    }

    @Test
    fun `a produce step is refused by a reader with nothing that lowers one`() {
        val thrown = shouldThrow<IllegalArgumentException> { produced().asSimulation() }

        withClue(thrown.message.orEmpty()) {
            thrown.message.orEmpty() shouldContain "place order"
            thrown.message.orEmpty() shouldContain "kestrel-plan-kafka"
        }
    }

    @Test
    fun `a produce step with no brokers is refused before the module that lowers it is missed`() {
        val thrown = shouldThrow<IllegalArgumentException> { produced(brokers = null).asSimulation() }

        withClue(thrown.message.orEmpty()) { thrown.message.orEmpty() shouldContain "brokers" }
    }

    @Test
    fun `an http step with no baseUrl is refused`() {
        val thrown = shouldThrow<IllegalArgumentException> { declaration(baseUrl = null).asSimulation() }

        withClue(thrown.message.orEmpty()) {
            thrown.message.orEmpty() shouldContain "baseUrl"
            thrown.message.orEmpty() shouldContain "browse"
        }
    }

    @Test
    fun `a goal may name a produce step`() {
        val thrown = shouldThrow<IllegalArgumentException> {
            produced(goals = listOf(DeclaredGoal.Percentile("place order", "p99", 2.seconds))).asSimulation()
        }

        withClue("a goal on a declared produce step is not the mistake here") {
            thrown.message.orEmpty() shouldContain "kestrel-plan-kafka"
        }
    }

    private fun produced(
        brokers: String? = "localhost:9092",
        goals: List<DeclaredGoal> = emptyList(),
    ) = Declaration(
        version = Declaration.VERSION,
        brokers = brokers,
        scenario = "orders",
        steps = listOf(DeclaredStep.Produce(name = "place order", topic = "orders", body = """{"cart":"1 anvil"}""")),
        load = DeclaredLoad.Constant(500.perSecond, 1.minutes),
        goals = goals,
    )

    private fun declaration(
        version: String = Declaration.VERSION,
        baseUrl: String? = "https://orders.internal",
        steps: List<DeclaredStep> = listOf(
            DeclaredStep.Request(name = "browse", method = "GET", path = "/products"),
            DeclaredStep.Request(
                name = "place order",
                method = "POST",
                path = "/orders",
                body = "{}",
                expecting = 201,
            ),
        ),
        load: DeclaredLoad = DeclaredLoad.Constant(50.perSecond, 1.minutes),
        goals: List<DeclaredGoal> = emptyList(),
    ) = Declaration(
        version = version,
        baseUrl = baseUrl,
        scenario = "checkout",
        steps = steps,
        load = load,
        goals = goals,
    )
}
