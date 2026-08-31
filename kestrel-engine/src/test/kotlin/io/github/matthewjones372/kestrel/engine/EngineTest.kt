package io.github.matthewjones372.kestrel.engine

import io.github.matthewjones372.kestrel.Scenario
import io.github.matthewjones372.kestrel.Step
import io.github.matthewjones372.kestrel.action
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.sessionKey
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class EngineTest {

    private val cart = sessionKey<String>("cart")
    private val order = sessionKey<Long>("order")

    @Test
    fun `a one-step scenario at one user a second counts that step once`() {
        val result = scenario("checkout") { exec("browse") { } }
            .at(1.perSecond, over = 1.seconds)
            .run()

        result["browse"].count shouldBe 1L
        result.ok shouldBe 1L
        result.failed shouldBe 0L
    }

    @Test
    fun `a step that declares a failure is counted under the reason it gave`() {
        val result = scenario("checkout") { exec("pay") { fail("503") } }
            .at(1.perSecond, over = 1.seconds)
            .run()

        result["pay"].failed.reasons shouldContainExactly mapOf("503" to 1L)
        result.failed shouldBe 1L
    }

    @Test
    fun `a step that throws is a failure named for the exception`() {
        val result = scenario("checkout") { exec("pay") { error("the target hung up") } }
            .at(1.perSecond, over = 1.seconds)
            .run()

        result["pay"].failed.reasons shouldContainExactly mapOf("java.lang.IllegalStateException" to 1L)
    }

    @Test
    fun `what one step puts in the session is there for the next`() {
        val result = scenario("checkout") {
            exec("browse") { set(cart, "two hats") }
            exec("pay") { if (this[cart] != "two hats") fail("no cart") }
        }.at(1.perSecond, over = 1.seconds).run()

        result["pay"].ok.count shouldBe 1L
    }

    @Test
    fun `a step that fails abandons the user, and the steps after it are not counted at all`() {
        val result = scenario("checkout") {
            exec("login") { fail("401") }
            exec("pay") { }
        }.at(1.perSecond, over = 1.seconds).run()

        result.steps.keys shouldBe setOf("login")
        result["login"].count shouldBe 1L
        result.count shouldBe 1L
    }

    @Test
    fun `a step that throws abandons the user as surely as one that says it failed`() {
        val result = scenario("checkout") {
            exec("login") { error("connection reset") }
            exec("pay") { }
        }.at(1.perSecond, over = 1.seconds).run()

        result.steps.keys shouldBe setOf("login")
    }

    @Test
    fun `a step inside a loop is timed once per iteration, under the one name it declares`() {
        val checkout = Scenario("checkout", listOf(Step.Repeat(3, listOf(Step.Exec("add to cart", action { })))))

        val result = checkout.at(1.perSecond, over = 1.seconds).run()

        result.steps.keys shouldBe setOf("add to cart")
        result["add to cart"].count shouldBe 3L
    }

    @Test
    fun `a failure inside a loop abandons the user rather than starting the next iteration`() {
        val adding = Step.Exec("add to cart", action { fail("503") })
        val checkout = Scenario("checkout", listOf(Step.Repeat(3, listOf(adding))))

        val result = checkout.at(1.perSecond, over = 1.seconds).run()

        result["add to cart"].count shouldBe 1L
    }

    @Test
    fun `a condition runs the steps under it for the user whose session satisfies it`() {
        val checkout = Scenario(
            "checkout",
            listOf(
                Step.Exec("browse", action { set(cart, "two hats") }),
                Step.When({ session -> session[cart] != null }, listOf(Step.Exec("pay", action { }))),
            ),
        )

        val result = checkout.at(1.perSecond, over = 1.seconds).run()

        result["pay"].count shouldBe 1L
    }

    @Test
    fun `a condition nobody satisfies records nothing for the steps under it`() {
        val checkout = Scenario(
            "checkout",
            listOf(
                Step.Exec("browse", action { }),
                Step.When({ session -> session[cart] != null }, listOf(Step.Exec("pay", action { }))),
            ),
        )

        val result = checkout.at(1.perSecond, over = 1.seconds).run()

        result.steps.keys shouldBe setOf("browse")
    }

    @Test
    fun `every request records how late it left against the departure it was promised`() {
        val result = scenario("checkout") { exec("browse") { } }
            .at(4.perSecond, over = 1.seconds)
            .run()

        result["browse"].count shouldBe 4L
        result.behind.count shouldBe 4L
    }

    @Test
    fun `an emit step is timed by its publish and does not wait for an answer`() {
        val result = scenario("trades") {
            emit("submitted", action { set(order, 7L) }, keyedBy = { session -> session[order] ?: 0L })
        }.at(1.perSecond, over = 1.seconds).run()

        result["submitted"].count shouldBe 1L
        result["submitted"].ok.count shouldBe 1L
        result["submitted"].unmatched shouldBe 0L
    }
}
