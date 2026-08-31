package io.github.matthewjones372.kestrel

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val browse = step("browse")
private val pay = step("pay")
private val search = step("search")

class PlanTest {

    private val browsing = scenario("browsing") { exec(search) { } }

    private val checkout = scenario("checkout") {
        exec(browse) { }
        exec(pay) { }
    }

    @Test
    fun `a simulation describes itself before it runs`() {
        val plan = checkout.at(50.perSecond, over = 1.minutes).plan()

        plan.scenario shouldBe "checkout"
        plan.steps shouldBe listOf("browse", "pay")
    }

    @Test
    fun `a plan names every step the tree declares, in order and each of them once`() {
        val looping = Scenario(
            "checkout",
            listOf(Step.Exec("browse", action { }), Step.Repeat(3, listOf(Step.Exec("add to cart", action { })))),
        )

        looping.at(1.perSecond, over = 1.seconds).plan().steps shouldBe listOf("browse", "add to cart")
    }

    @Test
    fun `a plan carries every arm of a mix, and adds up what all of them asked for`() {
        val mixed = checkout.at(50.perSecond, over = 1.minutes) + browsing.at(10.perSecond, over = 1.minutes)

        val plan = mixed.plan()

        plan.arms.map { it.scenario } shouldBe listOf("checkout", "browsing")
        plan.steps shouldBe listOf("browse", "pay", "search")
        plan.plannedUsers shouldBe 3600L
        withClue("each arm sends its own users through its own steps") {
            plan.plannedRequests shouldBe 6600L
        }
        plan.plannedWindow shouldBe 1.minutes
    }

    @Test
    fun `a one-armed plan reads as the arm it carries`() {
        val plan = checkout.at(50.perSecond, over = 1.minutes).plan()

        plan.arms.single() shouldBe PlannedArm("checkout", listOf("browse", "pay"), plan.profile)
    }

    @Test
    fun `a plan counts the requests the profile is asking for`() {
        val plan = checkout.at(50.perSecond, over = 1.minutes).plan()

        plan.plannedUsers shouldBe 3000L
        plan.plannedRequests shouldBe 6000L
    }

    @Test
    fun `a pause is not a request, so a plan that thinks does not plan more work`() {
        val thinking = scenario("checkout") {
            exec(browse) { }
            pause(2.seconds)
            exec(pay) { }
        }.at(50.perSecond, over = 1.minutes).plan()

        thinking.steps shouldBe listOf("browse", "pay")
        thinking.plannedRequests shouldBe 6000L
    }

    @Test
    fun `a plan says how long the profile promised between departures`() {
        checkout.at(50.perSecond, over = 1.minutes).plan().plannedInterval shouldBe 20.milliseconds

        withClue("a plan with no profile promised nothing, so there is no interval to keep") {
            Plan.none.plannedInterval shouldBe Duration.ZERO
        }
    }

    @Test
    fun `a shape made of stages is planned as the sum of them`() {
        val soak = hold(10.perSecond, over = 1.seconds).then(hold(20.perSecond, over = 1.seconds))

        checkout.injecting(soak).plan().plannedUsers shouldBe 30L
    }

    @Test
    fun `a result built by hand has a plan that claims nothing`() {
        val plan = Plan.none

        plan.scenario shouldBe ""
        plan.steps.shouldBeEmpty()
        plan.plannedRequests shouldBe 0L
    }

    @Test
    fun `the plan travels with the result, so a report can compare the two`() {
        val plan = checkout.at(2.perSecond, over = 1.seconds).plan()
        val result = RunResult(
            startedAt = java.time.Instant.parse("2026-08-26T09:00:00Z"),
            steps = emptyMap(),
            behind = Histogram().timing(),
            plan = plan,
        )

        result.plan.plannedRequests shouldBe 4L
        result.count shouldBe 0L
    }
}
