package io.github.matthewjones372.kestrel.report

import io.github.matthewjones372.kestrel.Goal
import io.github.matthewjones372.kestrel.goodput
import io.github.matthewjones372.kestrel.percent
import io.github.matthewjones372.kestrel.step
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class GoodputViewTest {

    private val pay = step("pay")
    private val never = step("refund")

    private val target = goodput(pay, under = 200.milliseconds) atLeast 99.percent

    private fun pageWith(vararg goals: Goal) = Fixtures.metItsTarget
        .let { it.copy(plan = it.plan.copy(goals = goals.toList())) }
        .toHtmlReport()

    @Test
    fun `a run that named no goodput target is not given one`() {
        Fixtures.metItsTarget.toHtmlReport() shouldNotContain """<section class="goodput""""
    }

    @Test
    fun `the page prints both the share that met the target and the rate it comes to`() {
        val page = pageWith(target)

        // One request in a hundred failed and one came back slow, over a
        // planned ten seconds.
        page shouldContain "98.0% · 9.8/s"
        page shouldContain "9.8/s</strong> under 200 ms across the run"
    }

    @Test
    fun `the page names the window the rate is over, because that is the fork it took`() {
        val page = pageWith(target)

        page shouldContain "over the 10.0 s the plan asked for"
        page shouldContain "rather than the span the run took"
    }

    @Test
    fun `the page says the bucket holding the target counts as having missed it`() {
        pageWith(target) shouldContain "The bucket holding the target counts as having missed it"
    }

    @Test
    fun `a target on a step that never ran says so rather than printing a share of nothing`() {
        pageWith(goodput(never, under = 200.milliseconds) atLeast 99.percent) shouldContain
            "Not measured — the step never ran."
    }

    @Test
    fun `a result that was never planned still has a share, and says why it has no rate`() {
        val page = Fixtures.metItsTarget
            .let { it.copy(plan = it.plan.copy(profile = null, goals = listOf(target))) }
            .toHtmlReport()

        page shouldContain "98.0%"
        page shouldNotContain "98.0% ·"
        page shouldContain "no window to report a rate over"
    }
}
