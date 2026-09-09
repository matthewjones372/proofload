package io.github.matthewjones372.proofload.report

import io.kotest.assertions.withClue
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class FailedViewTest {

    @Test
    fun `a run where nothing failed is not given a panel of zeroes`() {
        Fixtures.longEnoughForATail.toHtmlReport() shouldNotContain """<section class="failures""""
    }

    @Test
    fun `the failures report their own p99 beside the one the successes measured`() {
        val page = Fixtures.fellBehind.toHtmlReport()

        page shouldContain """<section class="failures""""
        page shouldContain "3 failed at 1.21 s p99, against 40.1 ms for the 2 that worked"
        withClue("browse failed nothing, so it is not a row of zeroes") {
            page shouldNotContain """<span class="failures-step">browse</span>"""
        }
    }

    @Test
    fun `a step that failed everything has nothing to compare its tail against`() {
        Fixtures.everythingFailed.toHtmlReport() shouldContain
            "5 failed at 1.21 s p99, and nothing succeeded to compare it against"
    }
}
