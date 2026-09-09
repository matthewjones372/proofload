package io.github.matthewjones372.proofload.report

import io.github.matthewjones372.proofload.SteadyState
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class SteadyViewTest {

    @Test
    fun `the page names the window it left out of the goals`() {
        val page = Fixtures.settledAfterAWarmUp.toHtmlReport()

        page shouldContain "Settled after 10.0 s"
        page shouldContain "judged over the remaining 10.0 s"
        withClue("and says the whole run is still in the table below it") {
            page shouldContain "the table below is the whole run"
        }
    }

    @Test
    fun `a run that never settled says so at the top of the page rather than failing`() {
        val page = Fixtures.neverSettled.toHtmlReport()

        page shouldContain "Never settled."
        page shouldContain "got slower and stayed slower"
        withClue("the verdict on its goals is still the run's own") { page shouldContain "verdict-headline" }
    }

    @Test
    fun `the tolerance the verdict came from is printed, and called a default`() {
        val page = Fixtures.settledAfterAWarmUp.toHtmlReport()

        page shouldContain SteadyState.TOLERANCE.asPercent()
        page shouldContain "a default rather than something this run discovered"
    }

    @Test
    fun `a run too short to judge says nothing about settling`() {
        val page = Fixtures.degradedHalfway.toHtmlReport()

        page shouldNotContain "Settled after"
        page shouldNotContain "Never settled"
    }

    @Test
    fun `a goal met over the segment and missed over the whole run is met, beside the whole run's number`() {
        val result = Fixtures.settledAfterAWarmUp

        withClue("service time over the whole run was ${result["pay"].serviceTime.p99}") {
            result.verdicts.single().met shouldBe true
        }
        val page = result.toHtmlReport()
        withClue("the table still prints what the whole run measured") { page shouldContain "200 ms" }
    }

    @Test
    fun `the payload carries the verdict the page printed`() {
        val page = Fixtures.settledAfterAWarmUp.toHtmlReport()

        page shouldContain """"settledAfter": 10000000000"""
        page shouldContain """"tolerance": ${SteadyState.TOLERANCE}"""
    }

    @Test
    fun `a run that never settled carries the reason into the payload, and no offset`() {
        val page = Fixtures.neverSettled.toHtmlReport()

        page shouldContain """"settledAfter": null"""
        page shouldContain """"why": "the last"""
    }
}
