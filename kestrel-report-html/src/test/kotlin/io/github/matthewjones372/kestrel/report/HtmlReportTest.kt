package io.github.matthewjones372.kestrel.report

import io.github.matthewjones372.kestrel.Change
import io.github.matthewjones372.kestrel.Comparison
import io.github.matthewjones372.kestrel.Floor
import io.github.matthewjones372.kestrel.Histogram
import io.github.matthewjones372.kestrel.Interval
import io.github.matthewjones372.kestrel.Machine
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.timing
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

class HtmlReportTest {

    private val stalls = Histogram().apply {
        repeat(95) { record(1.milliseconds) }
        repeat(4) { record(14.milliseconds) }
        record(30.milliseconds)
    }.timing()

    private val here = Machine(cores = 8, jdk = "21.0.2+13", os = "Linux", arch = "aarch64")

    private val interval = Interval(95.milliseconds, 99.milliseconds)

    private val better = Comparison.Compared(
        changes = listOf(Change.Better("pay", 100.milliseconds, 97.milliseconds, interval)),
        before = here,
        now = here,
    )

    @Test
    fun `the page for a run matches its golden`() {
        Fixtures.fellBehind.toHtmlReport() shouldBe Golden.text("report-behind.html")
    }

    @Test
    fun `the page for a run that lost records matches its golden`() {
        Fixtures.lostRecords.toHtmlReport() shouldBe Golden.text("report-lost.html")
    }

    @Test
    fun `a run that lost nothing is not made to print a zero for it`() {
        val page = Fixtures.fellBehind.toHtmlReport()

        page shouldNotContain "Records that never arrived"
        page shouldNotContain "In flight"
    }

    @Test
    fun `what the injector itself stalled for is on the page beside the backlog it could have caused`() {
        val page = Fixtures.stalled.toHtmlReport()

        page shouldContain "Injector stalled, p99"
        page shouldContain "stalled for 14.0 ms at p99 and 30.0 ms at worst"
        withClue("the stall belongs beside the backlog tile, not below the table") {
            page.indexOf("Injector stalled") shouldBeLessThan page.indexOf("<h2>Steps</h2>")
        }
    }

    @Test
    fun `a run nobody watched claims no stalls rather than showing a zero`() {
        val page = Fixtures.fellBehind.toHtmlReport()

        page shouldNotContain "Injector stalled"
    }

    @Test
    fun `the page says what this machine can resolve, and what it stalled for measuring that`() {
        val page = Fixtures.fellBehind.toHtmlReport(floor = Floor(resolution = 0.061, hiccups = stalls))

        page shouldContain "Calibrated on this machine: differences under <strong>6.10%</strong>"
        page shouldContain "stalls reached <strong>14.0 ms</strong> at p99"
    }

    @Test
    fun `a machine that cannot support a claim says so where the comparison would have been`() {
        val floor = Floor(resolution = 0.40, hiccups = stalls)

        val page = Fixtures.fellBehind.toHtmlReport(better, floor)

        page shouldContain "This machine cannot support a latency claim."
        withClue("a comparison nobody should act on is not printed smaller, it is not printed") {
            page shouldNotContain "<strong>better</strong>"
            page shouldNotContain "Calibrated on this machine"
        }
    }

    @Test
    fun `a run nobody calibrated compares as it always did`() {
        val page = Fixtures.fellBehind.toHtmlReport(better)

        page shouldContain "<strong>better</strong>"
        page shouldNotContain "cannot support a latency claim"
    }

    @Test
    fun `nothing on the page is fetched from anywhere`() {
        val page = Fixtures.fellBehind.toHtmlReport()

        withClue("a report that fetches anything renders blank from a CI artifact on a locked-down network") {
            page shouldNotContain "http://"
            page shouldNotContain "https://"
        }
    }

    @Test
    fun `a failure reason is text on the page, never markup`() {
        val page = Fixtures.fellBehind.toHtmlReport()

        page shouldNotContain "<b>"
        page shouldNotContain "</script> hung up"
        page shouldContain "status 503 &lt;b&gt;&quot;upstream&quot; &amp; &#39;down&#39;&lt;/b&gt;"
        page shouldContain "&lt;/script&gt; hung up"
    }

    @Test
    fun `the width of the bucket a percentile came from is printed beside it`() {
        Fixtures.fellBehind.toHtmlReport() shouldContain "0.78%"
    }

    @Test
    fun `percentiles are shown to the three figures the histogram has, not to six`() {
        val page = Fixtures.fellBehind.toHtmlReport()

        // 90177535 ns is the bucket top for a 90 ms sample.
        page shouldContain "90.2 ms"
        // 1207959551 ns, for 1200 ms.
        page shouldContain "1.21 s"
    }

    @Test
    fun `a run that recorded nothing says so rather than showing an empty table`() {
        val empty = RunResult(startedAt = Instant.EPOCH, steps = emptyMap(), behind = Histogram().timing())

        empty.toHtmlReport() shouldContain "recorded no steps"
    }

    @Test
    fun `writing the report creates the directories the path names`(@TempDir dir: Path) {
        val path = dir.resolve("reports/kestrel/checkout.html")

        val written = Fixtures.fellBehind.writeHtmlReport(path)

        written shouldBe path
        Files.readString(path) shouldBe Fixtures.fellBehind.toHtmlReport()
    }
}
