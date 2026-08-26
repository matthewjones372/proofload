package io.github.matthewjones372.kestrel.report

import io.github.matthewjones372.kestrel.failureRate
import io.github.matthewjones372.kestrel.keptSchedule
import io.github.matthewjones372.kestrel.p99
import io.github.matthewjones372.kestrel.percent
import io.github.matthewjones372.kestrel.step
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class VerdictsTest {

    private val pay = step("pay")

    private fun pageWith(vararg goals: io.github.matthewjones372.kestrel.Goal) =
        Fixtures.fellBehind.let { it.copy(plan = it.plan.copy(goals = goals.toList())) }.toHtmlReport()

    @Test
    fun `a run with no goals is not given a verdict it cannot support`() {
        // The stylesheet carries the class either way; the section is what is conditional.
        Fixtures.fellBehind.toHtmlReport() shouldNotContain "<section class=\"verdicts"
    }

    @Test
    fun `the page leads with how many goals were met`() {
        pageWith(p99(pay) under 10.seconds, keptSchedule) shouldContain "1 of 2 goals met."
    }

    @Test
    fun `a missed goal says by how much, so a reader knows if it is tuning or design`() {
        val page = pageWith(p99(pay) under 100.milliseconds)

        page shouldContain "% over"
        page shouldContain "missed"
    }

    @Test
    fun `a met goal still shows what was measured, not just a tick`() {
        pageWith(failureRate under 90.percent) shouldContain "the run failing under 90.0%"
    }

    @Test
    fun `all met reads differently from some missed`() {
        pageWith(p99(pay) under 10.seconds) shouldContain """class="verdicts met""""
        pageWith(p99(pay) under 1.milliseconds) shouldContain """class="verdicts missed""""
    }
}
