package io.github.matthewjones372.kestrel.report

import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.StepStats
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * A step that reported several answers from one run of its body is a stream,
 * and the page says so. Without it a stream and a loop read identically: both
 * make a step's count outnumber the users that reached it.
 */
class StreamViewTest {

    @Test
    fun `a step that sampled more than it ran is named as a stream`() {
        val page = Fixtures.fellBehind.withVisits { step ->
            if (step.name == "pay") step.count / 4 else step.count
        }.toHtmlReport()

        page shouldContain "reported more than one answer per run"
        page shouldContain "pay"
    }

    @Test
    fun `a run where every step ran once per answer says nothing about streams`() {
        Fixtures.fellBehind.withVisits { it.count }.toHtmlReport() shouldNotContain
            "reported more than one answer per run"
    }

    @Test
    fun `a run that counted no visits at all is not a run without streams`() {
        // Zero visits is a baseline written before visits existed, not a run
        // whose bodies never ran. Reading it as "no streams here" would put a
        // finding on the page that nothing measured.
        Fixtures.fellBehind.withVisits { 0L }.toHtmlReport() shouldNotContain
            "reported more than one answer per run"
    }
}

/** The same run with a visit count per step, which no fixture carries by default. */
private fun RunResult.withVisits(each: (StepStats) -> Long): RunResult =
    copy(steps = steps.mapValues { (_, step) -> step.copy(visits = each(step)) })
