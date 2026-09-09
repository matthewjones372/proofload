package io.github.matthewjones372.proofload.report

import io.kotest.assertions.withClue
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class TrendPageTest {

    private val page = Fixtures.trend.toHtmlReport()

    @Test
    fun `the page for a trend matches its golden`() {
        page.withoutStylesheet() matches "trend.html"
    }

    @Test
    fun `every point is on the page, with what its own runs support`() {
        Fixtures.trend.points.forEach { point ->
            withClue(point.label) {
                page shouldContain """data-point="${point.label}""""
                page shouldContain """<line class="band""""
            }
        }
    }

    @Test
    fun `a machine change is a break rather than a comparison`() {
        withClue("five adjacent pairs, one of which straddles the change of runner") {
            page shouldContain "the runner changed here"
            page shouldContain "<strong>4</strong> adjacent comparisons"
        }
    }

    @Test
    fun `the note says how many comparisons were made and how many the machine is expected to fake`() {
        page shouldContain "at 95%"
        withClue("four comparisons at 95% expects about 0.2") { page shouldContain "<strong>0.2</strong>" }
    }

    @Test
    fun `nothing is fitted, so no slope is quoted`() {
        page shouldContain "Nothing is fitted"
        page shouldNotContain "per week"
    }
}
