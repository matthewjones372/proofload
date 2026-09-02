package io.github.matthewjones372.kestrel.report

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * A staged run's one p99 is a mixture of populations. The page keeps it, and
 * puts each stage's own beside it.
 */
class StageViewTest {

    @Test
    fun `a staged run gets a row per stage, naming its rate line`() {
        val page = Fixtures.staged.toHtmlReport()

        page shouldContain "Stages"
        page shouldContain "100/s"
        page shouldContain "200/s"
    }

    @Test
    fun `the stage table says what its numbers are good to, which is not a step's`() {
        val page = Fixtures.staged.toHtmlReport()

        page shouldContain "read off rather than recorded"
    }

    @Test
    fun `a run nobody staged has no stage table`() {
        Fixtures.fellBehind.toHtmlReport() shouldNotContain "<h2>Stages</h2>"
    }
}
