package io.github.matthewjones372.proofload.report

import io.github.matthewjones372.proofload.Plan
import io.github.matthewjones372.proofload.RunRecorder
import io.github.matthewjones372.proofload.RunResult
import io.github.matthewjones372.proofload.hold
import io.github.matthewjones372.proofload.perSecond
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A gap between the users a run had and the users its own numbers predict is a
 * fault in the measurement, so the page has to blame the tool rather than the
 * target.
 */
class ConcurrencyViewTest {

    private fun ran(took: Duration, inFlight: Long): RunResult {
        val recorder = RunRecorder(Instant.parse("2026-08-26T09:00:00Z"))
        repeat(12 * 100) { request ->
            recorder.record("pay", null, took, Duration.ZERO, at = (request / 100).seconds)
        }
        return recorder.freeze().copy(
            plan = Plan("checkout", listOf("pay"), hold(100.perSecond, over = 12.seconds)),
            usersInFlight = List(12) { inFlight },
        )
    }

    @Test
    fun `a run whose numbers add up says the law holds`() {
        val page = ran(took = 50.milliseconds, inFlight = 5L).toHtmlReport()

        page shouldContain "Little's law holds on this run"
        page shouldContain "5.00 users were running"
    }

    @Test
    fun `a run whose numbers do not add up blames the tool, not the target`() {
        val page = ran(took = 100.milliseconds, inFlight = 5L).toHtmlReport()

        page shouldContain "These numbers do not add up."
        page shouldContain "a fault in the measurement rather than in the target"
    }

    @Test
    fun `a run that cannot be asked says nothing at all`() {
        Fixtures.fellBehind.toHtmlReport() shouldNotContain "Little's law"
    }
}
