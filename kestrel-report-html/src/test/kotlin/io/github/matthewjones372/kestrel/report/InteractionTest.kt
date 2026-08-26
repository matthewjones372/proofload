package io.github.matthewjones372.kestrel.report

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * The page's behaviour, asserted through the hooks its markup hands the
 * script. There is no browser here to click, so what is checked is that every
 * hook the script reaches for is present and that the script is inline.
 */
class InteractionTest {

    private val page = Fixtures.fellBehind.toHtmlReport()

    @Test
    fun `a run that fell behind says so before it shows a single percentile`() {
        val banner = page.indexOf("kestrel-behind")
        val firstPercentile = page.indexOf("<table")

        withClue("banner at $banner, table at $firstPercentile") {
            (banner in 0 until firstPercentile) shouldBe true
        }
        page shouldContain "late at p99"
    }

    @Test
    fun `a run that kept its schedule is not warned about`() {
        Fixtures.keptSchedule.toHtmlReport() shouldNotContain "kestrel-behind"
    }

    @Test
    fun `every column the script can sort by says which way it is sorted`() {
        page shouldContain """aria-sort="none""""
        page shouldContain """data-sort="count""""
        page shouldContain """data-sort="p99""""
    }

    @Test
    fun `a step row can be expanded because it is tied to its reasons by name`() {
        page shouldContain """data-step="pay""""
        page shouldContain """data-for="pay""""
        page shouldContain """aria-expanded="false""""
    }

    @Test
    fun `the timing toggle names both of the things it switches between`() {
        page shouldContain """id="mode-toggle""""
        page shouldContain """data-service-ns="""
        page shouldContain """data-response-ns="""
    }

    @Test
    fun `the script is inline, like everything else the page needs`() {
        page shouldContain "<script>"
        page shouldNotContain "<script src="
    }

    @Test
    fun `a step with no failures has nothing to expand`() {
        page shouldNotContain """data-for="browse""""
    }
}
