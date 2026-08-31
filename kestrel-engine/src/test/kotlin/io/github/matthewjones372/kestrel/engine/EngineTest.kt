package io.github.matthewjones372.kestrel.engine

import io.github.matthewjones372.kestrel.Arm
import io.github.matthewjones372.kestrel.Rate
import io.github.matthewjones372.kestrel.Said
import io.github.matthewjones372.kestrel.Scenario
import io.github.matthewjones372.kestrel.Simulation
import io.github.matthewjones372.kestrel.Step
import io.github.matthewjones372.kestrel.Threw
import io.github.matthewjones372.kestrel.action
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.constantRate
import io.github.matthewjones372.kestrel.feed
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.sessionKey
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

class EngineTest {

    private val cart = sessionKey<String>("cart")
    private val user = sessionKey<Long>("user")
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

        result["pay"].failed.reasons shouldContainExactly mapOf(Said("503") to 1L)
        result.failed shouldBe 1L
    }

    @Test
    fun `a step that throws is a failure named for the exception`() {
        val result = scenario("checkout") { exec("pay") { error("the target hung up") } }
            .at(1.perSecond, over = 1.seconds)
            .run()

        result["pay"].failed.reasons shouldContainExactly mapOf(Threw("java.lang.IllegalStateException") to 1L)
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
    fun `a step three iterations deep for each of ten users counts thirty requests and ten users`() {
        val checkout = Scenario("checkout", listOf(Step.Repeat(3, listOf(Step.Exec("add to cart", action { })))))

        val result = checkout.at(10.perSecond, over = 1.seconds).run()

        result["add to cart"].count shouldBe 30L
        withClue("a loop multiplies the requests and not the users that made them") {
            result["add to cart"].reached shouldBe 10L
        }
    }

    @Test
    fun `a step under a condition counts the users that satisfied it and no others`() {
        val result = scenario("checkout") {
            exec("browse") { set(cart, "two hats") }
            doIf({ session -> session[cart] != null }) { exec("pay") { } }
            doIf({ session -> session[order] != null }) { exec("refund") { } }
        }.at(4.perSecond, over = 1.seconds).run()

        result["browse"].reached shouldBe 4L
        result["pay"].reached shouldBe 4L
        result.ran("refund") shouldBe false
    }

    @Test
    fun `a during loop runs its body again for as long as its own clock has time left`() {
        val body = listOf(Step.Exec("poll", action { }), Step.Pause(20.milliseconds))
        val polling = Scenario("polling", listOf(Step.During(200.milliseconds, body)))

        val result = polling.at(1.perSecond, over = 1.seconds).run()

        withClue("a 200 ms window over a 20 ms pause is ten iterations idle and still two on a loaded machine") {
            result["poll"].count shouldBeGreaterThanOrEqualTo 2L
        }
    }

    @Test
    fun `a during loop whose window is already spent runs its body no times`() {
        val polling = Scenario("polling", listOf(Step.During(Duration.ZERO, listOf(Step.Exec("poll", action { })))))

        val result = polling.at(1.perSecond, over = 1.seconds).run()

        result.steps.keys shouldBe emptySet<String>()
    }

    @Test
    fun `a failure inside a during loop abandons the user rather than starting another iteration`() {
        val body = listOf(Step.Exec("poll", action { fail("503") }), Step.Pause(1.milliseconds))
        val polling = Scenario("polling", listOf(Step.During(200.milliseconds, body)))

        val result = polling.at(1.perSecond, over = 1.seconds).run()

        result["poll"].count shouldBe 1L
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
    fun `a step under a condition nobody satisfies is not a failure and not a skip, it is nothing`() {
        val result = scenario("checkout") {
            exec("browse") { }
            doIf({ session -> session[cart] != null }) { exec("pay") { } }
        }.at(1.perSecond, over = 1.seconds).run()

        withClue("a step nobody reached has no row to read a percentile off") {
            result.ran("pay") shouldBe false
        }
        result.count shouldBe 1L
        result.failed shouldBe 0L
    }

    @Test
    fun `a step under a condition its user satisfies runs where the tree puts it`() {
        val result = scenario("checkout") {
            exec("browse") { set(cart, "two hats") }
            doIf({ session -> session[cart] != null }) { exec("pay") { } }
        }.at(1.perSecond, over = 1.seconds).run()

        result["pay"].ok.count shouldBe 1L
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
    fun `a pause holds the user up between steps and is measured as nobody's latency`() {
        val thinking = 300.milliseconds
        val marks = ConcurrentLinkedQueue<Long>()

        val result = scenario("checkout") {
            exec("browse") { marks.add(System.nanoTime()) }
            pause(thinking)
            exec("pay") { marks.add(System.nanoTime()) }
        }.at(1.perSecond, over = 1.seconds).run()

        val (browsed, paid) = marks.toList()
        withClue("the user thought before its next request, so the gap is at least the pause") {
            (paid - browsed).nanoseconds shouldBeGreaterThanOrEqualTo thinking
        }
        withClue("a pause is nobody's request: it has no step of its own and no percentile") {
            result.steps.keys shouldBe setOf("browse", "pay")
            result["pay"].serviceTime.max shouldBeLessThan thinking
            result["pay"].responseTime.max shouldBeLessThan thinking
        }
        withClue("the generator is not late for a departure that was meant to wait") {
            result.behind.count shouldBe 2L
        }
    }

    @Test
    fun `a two-armed run sends both arms, each fed from its own feeder from user zero`() {
        val numbers = ConcurrentLinkedQueue<Pair<String, Long>>()

        val result = Simulation(listOf(arm("browse", 2.perSecond, numbers), arm("search", 1.perSecond, numbers))).run()

        result["browse"].count shouldBe 2L
        result["search"].count shouldBe 1L
        withClue("an arm's data is reproducible whatever rate the arms beside it are sent at") {
            numbers.filter { (arm, _) -> arm == "browse" }.map { (_, user) -> user }.sorted() shouldBe listOf(0L, 1L)
            numbers.filter { (arm, _) -> arm == "search" }.map { (_, user) -> user } shouldBe listOf(0L)
        }
    }

    @Test
    fun `a two-armed run departs in the merged order rather than one arm after the other`() {
        val numbers = ConcurrentLinkedQueue<Pair<String, Long>>()

        val result = Simulation(listOf(arm("browse", 2.perSecond, numbers), arm("search", 1.perSecond, numbers))).run()

        result.arrivals.count shouldBe 3L
        withClue("0s, 0s and 500ms merged is gaps of 0 and 500ms; booked arm after arm, one runs backwards") {
            result.arrivals.mean shouldBe 250.milliseconds
        }
    }

    private fun arm(name: String, rate: Rate, numbers: ConcurrentLinkedQueue<Pair<String, Long>>) = Arm(
        scenario(name) { exec(name) { numbers.add(name to (this[user] ?: -1L)) } },
        constantRate(rate, over = 1.seconds),
        feed(user) { it },
    )

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
