package io.github.matthewjones372.kestrel.report

import io.github.matthewjones372.kestrel.Histogram
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.timing
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class HtmlReportTest {

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
