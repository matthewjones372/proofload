package io.github.matthewjones372.proofload.report

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** Spec 0144: arrivals the generator refused are counted beside the failures, never inside them. */
class SaturatedViewTest {

    @Test
    fun `a run that refused arrivals says the generator was saturated and how many were not sent`() {
        val page = Fixtures.fellBehind.copy(refusedPerSecond = listOf(0L, 41_250L)).toHtmlReport()

        page shouldContain "The generator was saturated."
        page shouldContain "41,250 arrivals were not sent"
        page shouldContain "Not sent: generator saturated"
    }

    @Test
    fun `a run that refused nothing says nothing about it`() {
        val page = Fixtures.fellBehind.toHtmlReport()

        page shouldNotContain "saturated"
    }
}
