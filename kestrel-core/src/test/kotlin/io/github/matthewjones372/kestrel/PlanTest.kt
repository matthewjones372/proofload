package io.github.matthewjones372.kestrel

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val browse = step("browse")
private val pay = step("pay")

class PlanTest {

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
    fun `a plan counts the requests the profile is asking for`() {
        val plan = checkout.at(50.perSecond, over = 1.minutes).plan()

        plan.plannedUsers shouldBe 3000L
        plan.plannedRequests shouldBe 6000L
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
