package io.github.matthewjones372.proofload.report

import io.github.matthewjones372.proofload.Histogram
import io.github.matthewjones372.proofload.StepStats
import io.github.matthewjones372.proofload.timing
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * A percentile with an id beside it answers "show me one". Without it a reader
 * searches a tracing backend by timestamp, which is the search this replaces.
 */
class ExemplarViewTest {

    private val id = "4bf92f3577b34da6a3ce929d0e0e4736"

    private fun traced(): StepStats {
        val recorded = Histogram().apply {
            repeat(1_200) { record(20.milliseconds, trace = id) }
            record(900.milliseconds, trace = id)
        }.timing()
        return StepStats(
            name = "pay",
            ok = io.github.matthewjones372.proofload.Outcome(recorded, recorded, emptyMap()),
            failed = io.github.matthewjones372.proofload.Outcome.none,
            serviceTime = recorded,
            responseTime = recorded,
        )
    }

    @Test
    fun `the tail names a trace from the bucket it reports`() {
        val page = Fixtures.fellBehind.copy(steps = mapOf("pay" to traced())).toHtmlReport()

        page shouldContain "trace <code>$id</code>"
    }

    @Test
    fun `the p99 cell carries the trace, so a reader can copy it`() {
        val page = Fixtures.fellBehind.copy(steps = mapOf("pay" to traced())).toHtmlReport()

        page shouldContain """data-trace="$id""""
    }

    @Test
    fun `an untraced run says nothing about traces`() {
        Fixtures.fellBehind.toHtmlReport() shouldNotContain "data-trace"
    }
}
