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
