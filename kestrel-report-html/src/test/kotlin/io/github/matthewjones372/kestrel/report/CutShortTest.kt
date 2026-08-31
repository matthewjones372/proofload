package io.github.matthewjones372.kestrel.report

import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.constantRate
import io.github.matthewjones372.kestrel.perSecond
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Twenty seconds of run, against the window the plan asked for. */
class CutShortTest {

    private fun asking(window: Duration): RunResult {
        val run = Fixtures.settledAfterAWarmUp
        val asked = run.plan.arms.first().copy(profile = constantRate(25.perSecond, over = window))
        return run.copy(plan = run.plan.copy(arms = listOf(asked)))
    }

    @Test
    fun `a run cut short names the window it was given beside the one it was asked for`() {
        val page = asking(40.seconds).toHtmlReport()

        page shouldContain "<strong>Cut short.</strong> The schedule asked for 40.0 s and the run recorded 20.0 s"
    }

    @Test
    fun `a run that saw its window out says nothing extra`() {
        asking(20.seconds).toHtmlReport() shouldNotContain "Cut short"
    }

    @Test
    fun `a run that drained past its window is not a run that was cut short`() {
        asking(10.seconds).toHtmlReport() shouldNotContain "Cut short"
    }

    @Test
    fun `a shortfall the timeline's own seconds could account for is not worth a warning`() {
        asking(21.seconds).toHtmlReport() shouldNotContain "Cut short"
    }

    @Test
    fun `a result nobody recorded a timeline for has no measured window to be short of`() {
        val page = Fixtures.metItsTarget.toHtmlReport()

        page shouldContain "What was asked for"
        page shouldNotContain "Cut short"
    }
}
