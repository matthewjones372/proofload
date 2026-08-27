package io.github.matthewjones372.kestrel.report

import io.github.matthewjones372.kestrel.Capacity
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CapacityPageTest {

    @Test
    fun `the page for a capacity matches its golden`() {
        Fixtures.capacity.toHtmlReport() shouldBe Golden.text("capacity.html")
    }

    @Test
    fun `every rung that ran is on the page, at the rate it held`() {
        val page = Fixtures.capacity.toHtmlReport()

        Fixtures.capacity.curve.forEach { rung ->
            withClue("the rung at ${rung.rate.perSecond}/s") {
                page shouldContain """data-rate="${rung.rate.perSecond}""""
            }
        }
    }

    @Test
    fun `the rung that was chosen is marked, and the goal that stopped the climb is named`() {
        val page = Fixtures.capacity.toHtmlReport()

        page shouldContain "3,500/s"
        page shouldContain """class="rung passed chosen""""
        page shouldContain "pay p99 under 200ms"
    }

    @Test
    fun `a search a void rung stopped says so rather than printing the rate as an answer`() {
        val page = Fixtures.voidedCapacity.toHtmlReport()

        page shouldContain "void"
        withClue("the generator's own ceiling is not the target's, so no rate is claimed") {
            page shouldContain "no answer"
        }
    }

    @Test
    fun `a void rung names the interval it promised, so a reader can check the rule rather than trust it`() {
        val page = Fixtures.voidedCapacity.toHtmlReport()

        withClue("4,000/s over two minutes is a departure every 250 µs") {
            page shouldContain "a departure every 250 µs"
        }
        withClue("the lateness the rule was applied to is printed beside the threshold") {
            page shouldContain "80.2 ms at p99"
        }
    }

    @Test
    fun `every rung says how much load actually left against the rate it asked for`() {
        val page = Fixtures.capacity.toHtmlReport()

        page shouldContain """<th scope="col" class="num">Offered</th>"""
        withClue("100 µs of backlog still owed at the tail of two minutes held at 1,000/s") {
            page shouldContain """<td class="num">999.999/s</td>"""
        }
    }

    @Test
    fun `nothing on the page is fetched from anywhere`() {
        val page = Fixtures.capacity.toHtmlReport()

        page shouldNotContain "http://"
        page shouldNotContain "https://"
    }

    @Test
    fun `a search that ran no rungs says so rather than drawing an empty chart`() {
        Capacity(emptyList()).toHtmlReport() shouldContain "no rungs"
    }

    @Test
    fun `writing the page creates the directories the path names`(@TempDir dir: Path) {
        val path = dir.resolve("reports/kestrel/capacity.html")

        val written = Fixtures.capacity.writeHtmlReport(path)

        written shouldBe path
        Files.readString(path) shouldBe Fixtures.capacity.toHtmlReport()
    }
}
