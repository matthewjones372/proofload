package io.github.matthewjones372.kestrel.report

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class MixTest {

    private fun row(step: String, arm: String): String =
        """            <th scope="row">$step</th>""" + "\n" + """            <td class="arm">$arm</td>"""

    @Test
    fun `a step row names the arm that sent it`() {
        val page = Fixtures.mixed.toHtmlReport()

        page shouldContain """<button type="button">Arm</button>"""
        page shouldContain row("home", "browse")
        page shouldContain row("cart", "checkout")
    }

    @Test
    fun `a one-armed run carries no arm column, because a column of one repeated value is noise`() {
        val page = Fixtures.settledAfterAWarmUp.toHtmlReport()

        page shouldNotContain """<button type="button">Arm</button>"""
        page shouldNotContain """<td class="arm">"""
    }

    @Test
    fun `the page names every arm, not the first of them`() {
        Fixtures.mixed.toHtmlReport() shouldContain "<h1>browse + checkout</h1>"
    }

    @Test
    fun `the plan view prints the ratio each arm was asked for beside the one that departed`() {
        val page = Fixtures.mixed.toHtmlReport()

        // 160 of the 200 users planned browse, and 150 of the 195 counted took it.
        page shouldContain "80.00% asked, 76.92% departed"
        page shouldContain "20.00% asked, 23.08% departed"
    }

    @Test
    fun `a run that counted no users prints what was asked for and says the rest was not measured`() {
        val uncounted = Fixtures.mixed.let { run ->
            run.copy(steps = run.steps.mapValues { (_, step) -> step.copy(reached = 0L) })
        }

        val page = uncounted.toHtmlReport()

        page shouldContain "80.00% asked, — departed"
        page shouldContain "counted no users, so what departed cannot be split by arm"
    }

    @Test
    fun `the page for a mix matches its golden`() {
        Fixtures.mixed.toHtmlReport().withoutStylesheet() matches "report-mix.html"
    }
}
