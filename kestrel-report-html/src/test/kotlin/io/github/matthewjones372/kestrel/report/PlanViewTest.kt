package io.github.matthewjones372.kestrel.report

import io.github.matthewjones372.kestrel.Arrivals
import io.github.matthewjones372.kestrel.Plan
import io.github.matthewjones372.kestrel.hold
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.rampRate
import io.github.matthewjones372.kestrel.randomized
import io.github.matthewjones372.kestrel.then
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
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

    @Test
    fun `the page says arrivals were evenly spaced, and what a reader should read into that`() {
        val page = pageFor(held)

        page shouldContain "Arrivals were evenly spaced"
        page shouldContain "understates queueing against the same mean rate in production"
    }

    @Test
    fun `the page names the seed a randomised shape drew from`() {
        val page = pageFor(held.copy(profile = hold(120.perSecond, over = 4.seconds).randomized(seed = 20260826)))

        page shouldContain "Arrivals were drawn from seed 20260826."
        page shouldNotContain "evenly spaced"
    }

    @Test
    fun `the page prints the spacing that was measured, not the one the shape asked for`() {
        val measured = Arrivals(count = 480L, mean = 5.milliseconds, cov = 0.98)

        val page = Fixtures.fellBehind.copy(plan = held, arrivals = measured).toHtmlReport()

        page shouldContain "120/s held for 4.00 s"
        page shouldContain "Measured 5.00 ms between departures, coefficient of variation 0.98."
    }

    @Test
    fun `a run that departed nobody names what was asked for and measures nothing`() {
        val page = pageFor(held)

        page shouldContain "Arrivals were evenly spaced"
        page shouldNotContain "Measured"
    }
}
