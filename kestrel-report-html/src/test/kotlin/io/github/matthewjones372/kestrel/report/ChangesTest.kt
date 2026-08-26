package io.github.matthewjones372.kestrel.report

import io.github.matthewjones372.kestrel.Change
import io.github.matthewjones372.kestrel.Interval
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

class ChangesTest {

    private fun pageWith(vararg changes: Change) = Fixtures.fellBehind.toHtmlReport(changes.toList())

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
    fun `a step that stopped running is the loudest thing on the list`() {
        pageWith(Change.Gone("pay")) shouldContain "ran last time and did not run now"
    }

    @Test
    fun `a written report carries the comparison, not only an in-memory one`(@TempDir dir: Path) {
        val into = dir.resolve("report.html")

        Fixtures.fellBehind.writeHtmlReport(into, listOf(Change.Gone("pay")))

        Files.readString(into) shouldContain "ran last time and did not run now"
    }

    @Test
    fun `the page says what it compared and how, rather than leaving it implied`() {
        val page = pageWith(Change.Indistinguishable("pay", 1.milliseconds, 1.milliseconds))

        page shouldContain "95% sampling intervals"
        page shouldContain "the fix for that is a longer run"
    }
}
