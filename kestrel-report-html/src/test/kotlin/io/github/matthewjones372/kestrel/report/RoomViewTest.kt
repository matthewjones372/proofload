package io.github.matthewjones372.kestrel.report

import io.github.matthewjones372.kestrel.Headroom
import io.github.matthewjones372.kestrel.Limits
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * A run that exhausted its own descriptors was failing requests at this end of
 * the wire. The page has to say so above the counts, or a reader takes them
 * for the target's.
 */
class RoomViewTest {

    @Test
    fun `a run that ran out of room names the limit and what it reached`() {
        val page = Fixtures.fellBehind.copy(
            limits = Limits(openFiles = Headroom.Measured(peak = 61_120, limit = 65_536)),
        ).toHtmlReport()

        page shouldContain "The injector ran out of room."
        page shouldContain "open files reached 61,120 of 65,536"
    }

    @Test
    fun `a run with room to spare says nothing about it`() {
        val page = Fixtures.fellBehind.copy(
            limits = Limits(openFiles = Headroom.Measured(peak = 100, limit = 65_536)),
        ).toHtmlReport()

        page shouldNotContain "ran out of room"
    }

    @Test
    fun `a run that sampled nothing says nothing about it either`() {
        Fixtures.fellBehind.toHtmlReport() shouldNotContain "ran out of room"
    }

    @Test
    fun `a saturated CPU is in the JSON and does not raise the warning`() {
        val page = Fixtures.fellBehind.copy(
            limits = Limits(cpu = Headroom.Measured(peak = 400, limit = 400)),
        ).toHtmlReport()

        page shouldNotContain "ran out of room"
        page shouldContain """"peak": 400"""
    }

    @Test
    fun `an unmeasured limit carries its reason into the JSON rather than a zero`() {
        Fixtures.fellBehind.toHtmlReport() shouldContain """"because": "nothing sampled this run""""
    }
}
