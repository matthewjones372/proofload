package io.github.matthewjones372.proofload

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
        checkout.stepNames shouldBe listOf("browse", "add to cart")
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

        result shouldBe StepResult.Failed(Session.empty.set(orderId, 7L).set(page, "error"), Said("status 503"))
    }

    @Test
    fun `the first reason a step gives is the one it is reported under`() {
        val result = action {
            fail("status 503")
            fail("and then a timeout")
        }.run(Session.empty)

        result shouldBe StepResult.Failed(Session.empty, Said("status 503"))
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
    fun `a nested step is named once, where the tree puts it`() {
        val checkout = Scenario(
            "checkout",
            listOf(
                Step.Exec("browse", browse),
                Step.Repeat(3, listOf(Step.Exec("add to cart", browse))),
                Step.When({ session -> session[page] != null }, listOf(Step.Exec("pay", browse))),
            ),
        )

        checkout.stepNames shouldBe listOf("browse", "add to cart", "pay")
    }

    @Test
    fun `a name a run can record is readable however deep the tree buries it`() {
        val place = Step.Emit("place", browse, Correlation { session -> session[orderId] ?: 0L })
        val checkout = Scenario("checkout", listOf(Step.Repeat(2, listOf(Step.When({ true }, listOf(place))))))

        checkout.stepNames shouldBe listOf("place")
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
    fun `a repeat holds its body once, however many times the loop is to run it`() {
        val checkout = scenario("checkout") {
            exec("browse", browse)
            repeat(3) { exec("add to cart", browse) }
        }

        checkout.steps shouldBe listOf(
            Step.Exec("browse", browse),
            Step.Repeat(3, listOf(Step.Exec("add to cart", browse))),
        )
        checkout.stepNames shouldBe listOf("browse", "add to cart")
    }

    @Test
    fun `a loop that would run no times is refused where it is written`() {
        shouldThrow<IllegalArgumentException> { scenario("checkout") { repeat(0) { exec("add to cart", browse) } } }
    }

    @Test
    fun `a during loop carries the window it runs for beside the steps it runs`() {
        val polling = scenario("polling") {
            during(30.seconds) {
                exec("poll", browse)
                pause(1.seconds)
            }
        }

        polling.steps shouldBe listOf(
            Step.During(30.seconds, listOf(Step.Exec("poll", browse), Step.Pause(1.seconds))),
        )
        polling.stepNames shouldBe listOf("poll")
    }

    @Test
    fun `a window that runs backwards is refused where it is written, not where it is run`() {
        shouldThrow<IllegalArgumentException> { scenario("polling") { during(-(1.seconds)) { exec("poll", browse) } } }
    }

    @Test
    fun `doIf holds the steps it guards and the question it asks about the session`() {
        val checkout = scenario("checkout") {
            exec("browse", browse)
            doIf({ session -> session[orderId] != null }) { exec("pay", browse) }
        }

        val guard = checkout.steps.last()
        guard.shouldBeInstanceOf<Step.When>()
        guard.steps shouldBe listOf(Step.Exec("pay", browse))
        guard.predicate(Session.empty) shouldBe false
        guard.predicate(Session.empty.set(orderId, 7L)) shouldBe true
        checkout.stepNames shouldBe listOf("browse", "pay")
    }

    @Test
    fun `a condition can guard a loop, and a loop can hold a condition`() {
        val checkout = scenario("checkout") {
            doIf({ session -> session[page] != null }) {
                repeat(2) { exec("add to cart", browse) }
            }
        }

        checkout.stepNames shouldBe listOf("add to cart")
    }

    @Test
    fun `the steps inside a loop are frozen with the scenario around them`() {
        val checkout = scenario("checkout") { repeat(2) { exec("add to cart", browse) } }

        @Suppress("UNCHECKED_CAST")
        val body = (checkout.steps.single() as Step.Repeat).steps as MutableList<Step>

        shouldThrow<UnsupportedOperationException> { body.add(Step.Exec("injected", browse)) }
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
