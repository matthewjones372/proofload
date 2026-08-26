package io.github.matthewjones372.kestrel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private val page = sessionKey<String>("page")

class ScenarioTest {

    private val browse = Action { session -> session.set(page, "home").ok() }

    @Test
    fun `a scenario keeps its steps in the order they were declared`() {
        val checkout = scenario("checkout") {
            exec("browse", browse)
            exec("add to cart") { session -> session.ok() }
        }

        checkout.name shouldBe "checkout"
        checkout.steps.map { it.name } shouldBe listOf("browse", "add to cart")
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
    fun `the step list a scenario hands out is frozen`() {
        val checkout = scenario("checkout") { exec("browse", browse) }

        // The cast is what a Java caller can do for free; the freeze is what stops it.
        @Suppress("UNCHECKED_CAST")
        val steps = checkout.steps as MutableList<Step>

        shouldThrow<UnsupportedOperationException> { steps.add(Step.Exec("injected", browse)) }
    }

    @Test
    fun `a step names itself, so a report has a row that is not a URL`() {
        val step = scenario("s") { exec("browse", browse) }.steps.single()

        when (step) {
            is Step.Exec -> step.name shouldBe "browse"
        }
    }
}
