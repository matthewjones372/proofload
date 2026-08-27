package io.github.matthewjones372.kestrel.report

import io.github.matthewjones372.kestrel.Change
import io.github.matthewjones372.kestrel.Comparison
import io.github.matthewjones372.kestrel.Interval
import io.github.matthewjones372.kestrel.Machine
import io.github.matthewjones372.kestrel.Probe
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

class ChangesTest {

    private val here = Machine(cores = 8, jdk = "21.0.2+13", os = "Linux", arch = "aarch64")

    private fun comparisonOf(vararg changes: Change, before: Machine = here) =
        Comparison.Compared(changes.toList(), before = before, now = here)

    private fun pageWith(vararg changes: Change) = Fixtures.fellBehind.toHtmlReport(comparisonOf(*changes))

    @Test
    fun `a first run has nothing to compare against and says nothing`() {
        // The stylesheet carries the class either way; the section is what is conditional.
        Fixtures.fellBehind.toHtmlReport() shouldNotContain "<section class=\"changes"
    }

    @Test
    fun `a run that moved nothing says so plainly`() {
        val page = pageWith(Change.Indistinguishable("pay", 290.milliseconds, 302.milliseconds))

        page shouldContain "Nothing measurably changed since the last run."
        page shouldContain "not distinguishable"
    }

    @Test
    fun `a real regression is called worse and shows the interval that proves it`() {
        val page = pageWith(
            Change.Worse("pay", 290.milliseconds, 890.milliseconds, Interval(810.milliseconds, 960.milliseconds)),
        )

        page shouldContain "1 of 1 step measurably changed"
        page shouldContain "810 ms–960 ms"
        page shouldContain "worse"
    }

    @Test
    fun `a runner half as fast is named above the steps it would otherwise be blamed on`() {
        val interval = Interval(810.milliseconds, 960.milliseconds)
        val worse = Change.Worse("pay", 290.milliseconds, 890.milliseconds, interval)
        val page = Fixtures.fellBehind.toHtmlReport(
            Comparison.Compared(
                changes = listOf(worse),
                before = here,
                now = here,
                beforeProbe = Probe(50.microseconds),
                nowProbe = Probe(100.microseconds),
            ),
        )

        page shouldContain "ran a fixed probe 2.00 times slower"
        withClue("the runner is the first thing read, or the step is read as the finding") {
            page.indexOf("fixed probe") shouldBeLessThan page.indexOf("""<li class="worse">""")
        }
    }

    @Test
    fun `a step that stopped running is the loudest thing on the list`() {
        pageWith(Change.Gone("pay")) shouldContain "ran last time and did not run now"
    }

    @Test
    fun `a written report carries the comparison, not only an in-memory one`(@TempDir dir: Path) {
        val into = dir.resolve("report.html")

        Fixtures.fellBehind.writeHtmlReport(into, comparisonOf(Change.Gone("pay")))

        Files.readString(into) shouldContain "ran last time and did not run now"
    }

    @Test
    fun `the page says what it compared and how, rather than leaving it implied`() {
        val page = pageWith(Change.Indistinguishable("pay", 1.milliseconds, 1.milliseconds))

        page shouldContain "95% sampling intervals"
        page shouldContain "the fix for that is a longer run"
    }

    @Test
    fun `a comparison that was refused prints the reason rather than an empty section`() {
        val page = Fixtures.fellBehind.toHtmlReport(
            Comparison.NotComparable("these runs were not asked to do the same thing: scenario was browsing"),
        )

        page shouldContain "not asked to do the same thing"
        page shouldContain "browsing"
    }

    @Test
    fun `a comparison across two machines is shown, with the runner named as the warning`() {
        val page = Fixtures.fellBehind.toHtmlReport(
            comparisonOf(Change.Gone("pay"), before = here.copy(cores = 4)),
        )

        page shouldContain "ran last time and did not run now"
        page shouldContain "may be the runner"
        page shouldContain "4 cores"
    }

    @Test
    fun `a comparison on the one machine says nothing about runners`() {
        pageWith(Change.Gone("pay")) shouldNotContain "may be the runner"
    }
}
