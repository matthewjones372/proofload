package io.github.matthewjones372.proofload.report

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * What the page says about its own numbers. Each sentence is arithmetic over
 * counts the run already has, and each appears only when its criterion holds.
 */
class ReadingTest {

    private val behind = Fixtures.fellBehind.toHtmlReport()
    private val onTime = Fixtures.keptSchedule.toHtmlReport()

    @Test
    fun `a run that fell behind says the latencies are not all the target's`() {
        behind shouldContain "fell behind its own schedule"
        behind shouldContain "rather than time the target took"
    }

    @Test
    fun `a run that kept its schedule says the latencies are the target's`() {
        onTime shouldContain "kept to its schedule"
        onTime shouldNotContain "fell behind its own schedule"
    }

    @Test
    fun `the slowest step is named, so nobody has to scan the table for it`() {
        behind shouldContain "is the slowest step"
        behind shouldContain "pay"
    }

    @Test
    fun `a percentile resting on a handful of requests says so`() {
        behind shouldContain "p99 rests on"
        behind shouldContain "hint rather than a number"
    }

    @Test
    fun `the percentile columns carry the weight behind them`() {
        behind shouldContain "p99 (1)"
    }

    @Test
    fun `sorting still works on a column whose heading carries a count`() {
        behind shouldContain """data-sort="p99""""
    }

    @Test
    fun `a journey is described in users rather than in percentiles`() {
        behind shouldContain "meets a p99 somewhere in the journey"
        behind shouldContain "Percentiles do not add"
    }

    @Test
    fun `one request is one request, not one requests`() {
        behind shouldContain "p99 rests on 1 request<"
    }

    @Test
    fun `no average appears anywhere, because an average hides what a load test looks for`() {
        behind shouldNotContain "average"
        behind shouldNotContain "mean "
    }
}
