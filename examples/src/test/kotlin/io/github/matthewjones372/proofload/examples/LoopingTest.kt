package io.github.matthewjones372.proofload.examples

import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.engine.run
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.scenario
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

/**
 * A loop bounded by a window against a real clock. What the engine's own tests
 * pin is that the body runs again; what this adds is that the window it runs
 * for is the loop's rather than the profile's.
 */
@Tag("timing")
class LoopingTest {

    @Test
    fun `a during loop runs for the window it named, and the run waits for it`() {
        val polling = scenario("polling") {
            during(400.milliseconds) {
                exec("poll") { }
                pause(50.milliseconds)
            }
        }

        val startedAt = System.nanoTime()
        val result = polling.at(10.perSecond, over = 100.milliseconds).run()
        val took = (System.nanoTime() - startedAt).nanoseconds

        withClue("the profile asked for 100 ms of departures and the loop for 400 ms of polling") {
            took shouldBeGreaterThanOrEqualTo 400.milliseconds
        }
        withClue("${result["poll"].count} polls from ${result.plan.plannedUsers} users") {
            result["poll"].count shouldBeGreaterThanOrEqualTo result.plan.plannedUsers * 2
        }
    }
}
