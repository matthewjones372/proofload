package io.github.matthewjones372.kestrel.report

import io.github.matthewjones372.kestrel.Plan
import io.github.matthewjones372.kestrel.hold
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.rampRate
import io.github.matthewjones372.kestrel.then
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class PlanViewTest {

    private fun pageFor(plan: Plan) = Fixtures.fellBehind.copy(plan = plan).toHtmlReport()

    private val held = Plan(
        scenario = "checkout",
        steps = listOf("browse", "pay"),
        profile = hold(120.perSecond, over = 4.seconds),
    )

    @Test
    fun `the page names the scenario and the shape it was asked for`() {
        val page = pageFor(held)

        page shouldContain "<strong>checkout</strong>"
        page shouldContain "2 steps"
        page shouldContain "120/s held for 4.00 s"
    }

    @Test
    fun `the page says how much was planned, so a shortfall has something to be short of`() {
        pageFor(held) shouldContain "Planned 480 users, 960 requests"
    }

    @Test
    fun `a run that sent less than it planned says so, and by how much`() {
        val page = pageFor(held)

        page shouldContain "planned requests never went out"
        page shouldContain "lighter run than the one that was asked for"
    }

    @Test
    fun `a run that sent everything says that instead`() {
        val page = pageFor(held.copy(profile = hold(1.perSecond, over = 4.seconds)))

        page shouldContain "The run sent all"
        page shouldNotContain "never went out"
    }

    @Test
    fun `a result nobody planned shows no plan rather than an empty one`() {
        pageFor(Plan.none) shouldNotContain "What was asked for"
    }

    @Test
    fun `a shape of several stages is described in the order they run`() {
        val soak = rampRate(from = 0.perSecond, to = 200.perSecond, over = 1.minutes)
            .then(hold(200.perSecond, over = 10.minutes))

        val page = pageFor(held.copy(profile = soak))

        page shouldContain "0/s to 200/s over 1.0 min, then 200/s held for 10.0 min"
        page shouldContain "polyline"
    }
}
