package io.github.matthewjones372.proofload.examples

import io.github.matthewjones372.proofload.Progress
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.engine.run
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.scenario
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The timeline against a real clock. Its arithmetic is tested against recorded
 * offsets in `proofload-core`; what this adds is that the offsets the engine
 * hands it are the ones a wall clock would agree with.
 */
@Tag("timing")
class TimelineOverTimeTest {

    @Test
    fun `a run given three seconds of departures reports a second for each of them`() {
        // Silent, as the framework modules are: this is a test, and its output
        // is the JUnit report rather than a terminal somebody is watching.
        val result = scenario("timeline") { exec("browse") { } }
            .at(20.perSecond, over = 3.seconds)
            .run(Progress.silent)

        // At least, rather than exactly: a loaded machine can push the last
        // departures into a fourth second, and that is the timeline reporting
        // what happened rather than what was asked for.
        withClue("${result.timeline.size} seconds for ${result.count} requests") {
            (result.timeline.size >= 3) shouldBe true
        }

        result.timeline.sumOf { it.count } shouldBe result.count
        result.timeline.first().count shouldBe result.timeline.first().ok
    }
}
