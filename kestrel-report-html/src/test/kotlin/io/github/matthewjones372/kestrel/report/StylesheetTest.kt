package io.github.matthewjones372.kestrel.report

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * The stylesheet is asserted once, and each page golden asserts the page.
 *
 * A new rule then moves exactly one file and its diff is the rule, rather than
 * moving every page and leaving a reader to spot which of a few hundred lines
 * of inlined CSS changed.
 */
class StylesheetTest {

    /** Every page this module writes, rendered. */
    private val pages = listOf(
        "the run report" to Fixtures.fellBehind.toHtmlReport(),
        "the capacity page" to Fixtures.capacity.toHtmlReport(),
        "the trend page" to Fixtures.trend.toHtmlReport(),
    )

    @Test
    fun `the stylesheet matches its own golden`() {
        REPORT_CSS matches "stylesheet.css"
    }

    @Test
    fun `every page carries it, because a report that fetches anything renders blank from an artifact`() {
        pages.forEach { (which, page) ->
            withClue(which) { page.carriesTheStylesheet() shouldBe true }
        }
    }

    @Test
    fun `a page that stopped inlining it is caught rather than made invisible by the split`() {
        val stripped = Fixtures.fellBehind.toHtmlReport().withoutStylesheet()

        withClue("the page goldens no longer hold the styles, so this is the only thing watching") {
            stripped.carriesTheStylesheet() shouldBe false
        }
    }

    @Test
    fun `what a page golden holds is the page, and none of the stylesheet`() {
        val golden = Golden.text("report-behind.html")

        withClue("a rule added to Assets.kt must move stylesheet.css and nothing else") {
            golden shouldContain "<style>\n</style>"
            golden.carriesTheStylesheet() shouldBe false
        }
    }

    @Test
    fun `a page with no style block at all is a failure rather than a silent pass`() {
        val why = shouldThrow<IllegalArgumentException> { "<html><body>nothing</body></html>".withoutStylesheet() }

        withClue(why.message.orEmpty()) { why.message.orEmpty() shouldContain "no style block" }
    }
}
