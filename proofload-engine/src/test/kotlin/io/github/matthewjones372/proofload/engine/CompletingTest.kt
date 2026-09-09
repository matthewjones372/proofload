package io.github.matthewjones372.proofload.engine

import io.github.matthewjones372.proofload.InMemoryCompletions
import io.github.matthewjones372.proofload.Scenario
import io.github.matthewjones372.proofload.action
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.completing
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.sessionKey
import io.github.matthewjones372.proofload.step
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val order = sessionKey<Long>("order")
private val submitted = step("submitted")
private val settled = step("settled")

class CompletingTest {

    private fun publishing(sink: InMemoryCompletions?, taking: Long = 0L): Scenario {
        val ids = AtomicLong()
        return scenario("trades") {
            emit(
                submitted,
                action {
                    val id = ids.incrementAndGet()
                    set(order, id)
                    if (taking > 0L) Thread.sleep(taking)
                    sink?.observe(id)
                },
                keyedBy = { session -> session[order] ?: 0L },
            )
        }
    }

    @Test
    fun `a record the sink answers for is recorded under the step that was waiting for it`() {
        val sink = InMemoryCompletions()

        val result = publishing(sink)
            .at(1.perSecond, over = 1.seconds)
            .completing(settled, from = sink, drainingFor = 500.milliseconds)
            .run()

        result[settled].count shouldBe 1L
        result[settled].unmatched shouldBe 0L
        result[settled].inFlight shouldBe 0L
    }

    @Test
    fun `an answer is timed from the departure the profile promised, not from the run's start`() {
        val sink = InMemoryCompletions()

        val result = publishing(sink)
            .at(2.perSecond, over = 1.seconds)
            .completing(settled, from = sink, drainingFor = 500.milliseconds)
            .run()

        // The second user departs half a second in and its record is observed
        // as it publishes. Timed from the run's start that would read as half a
        // second; from the departure it was promised it is the pipeline's own.
        withClue("latency was ${result[settled].serviceTime.max}") {
            (result[settled].serviceTime.max < 400.milliseconds) shouldBe true
        }
        result[settled].count shouldBe 2L
    }

    @Test
    fun `a record the sink never answers for is counted as gone, not left out`() {
        val result = publishing(sink = null)
            .at(1.perSecond, over = 1.seconds)
            .completing(settled, from = InMemoryCompletions(), drainingFor = 500.milliseconds)
            .run()

        result[settled].count shouldBe 0L
        result[settled].unmatched shouldBe 1L
        result[settled].inFlight shouldBe 0L
    }

    @Test
    fun `a record that left after the run's window was over is still moving, not lost`() {
        val result = publishing(sink = null, taking = 1_400L)
            .at(1.perSecond, over = 1.seconds)
            .completing(settled, from = InMemoryCompletions(), drainingFor = 500.milliseconds)
            .run()

        result[settled].unmatched shouldBe 0L
        result[settled].inFlight shouldBe 1L
    }

    @Test
    fun `a run that names no sink waits for nothing and reports no step for it`() {
        val result = publishing(sink = null).at(1.perSecond, over = 1.seconds).run()

        result.ran(settled) shouldBe false
        result[submitted].count shouldBe 1L
    }
}
