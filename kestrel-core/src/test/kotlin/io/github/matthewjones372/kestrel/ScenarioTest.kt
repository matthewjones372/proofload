package io.github.matthewjones372.kestrel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

private val page = sessionKey<String>("page")
private val orderId = sessionKey<Long>("orderId")

class ScenarioTest {

    private val browse = action { set(page, "home") }

    @Test
    fun `a scenario keeps its steps in the order they were declared`() {
        val checkout = scenario("checkout") {
            exec("browse") { set(page, "home") }
            exec("add to cart") { set(page, "cart") }
        }

        checkout.name shouldBe "checkout"
        checkout.steps.map { it.name } shouldBe listOf("browse", "add to cart")
    }

    @Test
    fun `a step body reads and writes the session without naming it`() {
        val step = action {
            set(orderId, 7L)
            set(page, "order ${get(orderId)}")
        }

        step.run(Session.empty) shouldBe StepResult.Ok(Session.empty.set(orderId, 7L).set(page, "order 7"))
    }

    @Test
    fun `a step that says nothing succeeded`() {
        action { }.run(Session.empty) shouldBe StepResult.Ok(Session.empty)
    }

    @Test
    fun `a failed step carries on to the end and keeps what it set`() {
        val result = action {
            set(orderId, 7L)
            fail("status 503")
            set(page, "error")
        }.run(Session.empty)

        result shouldBe StepResult.Failed(Session.empty.set(orderId, 7L).set(page, "error"), "status 503")
    }

    @Test
    fun `the first reason a step gives is the one it is reported under`() {
        val result = action {
            fail("status 503")
            fail("and then a timeout")
        }.run(Session.empty)

        result shouldBe StepResult.Failed(Session.empty, "status 503")
    }

    @Test
    fun `two scenarios built from the same steps are equal`() {
        val one = scenario("checkout") { exec("browse", browse) }
        val other = scenario("checkout") { exec("browse", browse) }

        one shouldBe other
    }

    @Test
    fun `a scenario differing only in step order is not equal`() {
        val forwards = scenario("s") { exec("a", browse); exec("b", browse) }
        val backwards = scenario("s") { exec("b", browse); exec("a", browse) }

        (forwards == backwards) shouldBe false
    }

    @Test
    fun `a pause is a step value, so building a scenario that waits an hour waits for nothing`() {
        val checkout = scenario("checkout") {
            exec("browse", browse)
            pause(1.hours)
            exec("pay", browse)
        }

        checkout.steps shouldBe listOf(Step.Exec("browse", browse), Step.Pause(1.hours), Step.Exec("pay", browse))
    }

    @Test
    fun `a pause that runs backwards is refused where it is written, not where it is run`() {
        shouldThrow<IllegalArgumentException> { scenario("checkout") { pause(-(1.seconds)) } }
    }

    @Test
    fun `the step list a scenario hands out is frozen`() {
        val checkout = scenario("checkout") { exec("browse", browse) }

        // The cast is what a Java caller can do for free; the freeze is what stops it.
        @Suppress("UNCHECKED_CAST")
        val steps = checkout.steps as MutableList<Step>

        shouldThrow<UnsupportedOperationException> { steps.add(Step.Exec("injected", browse)) }
    }
}
