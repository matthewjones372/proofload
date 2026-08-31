package io.github.matthewjones372.kestrel.report

import io.github.matthewjones372.kestrel.Difference
import io.github.matthewjones372.kestrel.Machine
import io.github.matthewjones372.kestrel.Share
import io.github.matthewjones372.kestrel.Spread
import io.github.matthewjones372.kestrel.Statistic
import io.github.matthewjones372.kestrel.Tell
import io.github.matthewjones372.kestrel.goodput
import io.github.matthewjones372.kestrel.p95
import io.github.matthewjones372.kestrel.p99
import io.github.matthewjones372.kestrel.percent
import io.github.matthewjones372.kestrel.step
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

private val pay = step("pay")

class DifferencesTest {

    private val here = Machine(cores = 8, jdk = "21.0.2+13", os = "Linux", arch = "aarch64")

    private fun difference(
        statistic: Statistic,
        before: Double,
        now: Double,
        interval: Spread?,
        acceptable: Share = 3.percent,
        refused: Tell.CannotTell? = null,
        on: Machine = here,
    ) = Difference(
        statistic = statistic,
        before = before,
        now = now,
        runs = 10,
        baselineRuns = 10,
        acceptable = acceptable,
        machine = on,
        baselineMachine = here,
        interval = interval,
        refused = refused,
    )

    private val worse = difference(
        statistic = p99(pay),
        before = 290.0,
        now = 305.0,
        interval = Spread(low = 1.041, high = 1.065),
    )

    private val better = difference(
        statistic = p95(pay),
        before = 100.0,
        now = 90.0,
        interval = Spread(low = 0.87, high = 0.93),
    )

    private val cannotTell = difference(
        statistic = goodput(pay, under = 200.milliseconds),
        before = 0.99,
        now = 0.985,
        interval = Spread(low = 0.98, high = 1.01),
    )

    private val tooFewRuns = difference(
        statistic = p99(step("browse")),
        before = 200.0,
        now = 210.0,
        interval = null,
        refused = Tell.CannotTell(
            why = "only 3 runs here and 10 in the baseline",
            wouldChangeIt = "five runs a side, at least",
        ),
    )

    private val all = listOf(worse, better, cannotTell, tooFewRuns)

    @Test
    fun `the page for a run compared against the runs before matches its golden`() {
        Fixtures.metItsTarget.toHtmlReport(differences = all) matches "report-differences.html"
    }

    @Test
    fun `a page with nothing to compare carries no comparison`() {
        Fixtures.metItsTarget.toHtmlReport() shouldNotContain "Against the runs before"
    }

    @Test
    fun `a statistic that moved past what was declared acceptable is called worse`() {
        val page = listOf(worse).differenceLines().joinToString(separator = "\n")

        page shouldContain "pay p99 is <strong>5.17% slower</strong>"
        page shouldContain "(4.10% to 6.50%, 10 runs against 10)"
        page shouldContain "<strong>Worse</strong> than the 3% that was declared acceptable"
    }

    @Test
    fun `a statistic that moved the other way is called better, and reads as faster`() {
        val page = listOf(better).differenceLines().joinToString(separator = "\n")

        page shouldContain "pay p95 is <strong>10.00% faster</strong>"
        page shouldContain "<strong>Better</strong> than the 3%"
    }

    @Test
    fun `less goodput reads as less rather than as faster`() {
        val page = listOf(cannotTell).differenceLines().joinToString(separator = "\n")

        page shouldContain "goodput under 200ms is <strong>0.51% less</strong>"
    }

    @Test
    fun `every verdict that cannot tell says what would change it`() {
        val page = all.differenceLines().joinToString(separator = "\n")

        page shouldContain "<strong>Cannot tell</strong>: the whole interval is inside the 3% that was " +
            "declared acceptable. What would change it: a smaller threshold"
        page shouldContain "browse p99: <strong>cannot tell</strong>. only 3 runs here and 10 in the " +
            "baseline. What would change it: five runs a side, at least."
    }

    @Test
    fun `the headline counts the statistics these runs could tell about`() {
        all.differenceLines().joinToString(separator = "\n") shouldContain "2 of 4 statistics moved"
        listOf(cannotTell).differenceLines().joinToString(separator = "\n") shouldContain
            "Nothing here moved by more than these runs can tell."
    }

    @Test
    fun `a difference measured on another machine carries the caveat above the lines`() {
        val elsewhere = listOf(worse, difference(p95(pay), 100.0, 90.0, Spread(0.87, 0.93), on = here.copy(cores = 2)))

        elsewhere.differenceLines().joinToString(separator = "\n") shouldContain "may be the runner"
    }

    @Test
    fun `nothing to compare prints nothing`() {
        emptyList<Difference>().differenceLines() shouldBe emptyList()
    }
}
