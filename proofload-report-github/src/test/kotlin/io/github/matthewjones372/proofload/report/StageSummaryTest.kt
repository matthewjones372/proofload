package io.github.matthewjones372.proofload.report

import io.github.matthewjones372.proofload.Plan
import io.github.matthewjones372.proofload.RunRecorder
import io.github.matthewjones372.proofload.RunResult
import io.github.matthewjones372.proofload.hold
import io.github.matthewjones372.proofload.inEveryStage
import io.github.matthewjones372.proofload.p99
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.step
import io.github.matthewjones372.proofload.then
import io.kotest.assertions.withClue
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A staged run's one p99 is a mixture of the stages. The summary keeps it and
 * says what it is a mixture of.
 */
class StageSummaryTest {

    private fun ran(staged: Boolean): RunResult {
        val recorder = RunRecorder(Instant.parse("2026-08-26T09:00:00Z"))
        repeat(4) { second ->
            recorder.record(
                step = "pay",
                failure = null,
                serviceTime = if (second < 2) 10.milliseconds else 100.milliseconds,
                schedulingDelay = Duration.ZERO,
                at = second.seconds,
            )
        }
        val shape = if (staged) {
            hold(100.perSecond, over = 2.seconds).then(hold(200.perSecond, over = 2.seconds))
        } else {
            hold(100.perSecond, over = 4.seconds)
        }
        return recorder.freeze().copy(plan = Plan("paying", listOf("pay"), shape))
    }

    @Test
    fun `a staged run's summary carries a row per stage and says what they are good to`() {
        val summary = ran(staged = true).markdown()

        summary shouldContain "| Stage "
        summary shouldContain "| 1 of 2 |"
        summary shouldContain "| 2 of 2 |"
        withClue("the width is the timeline's, not a step's, and the summary says so") {
            summary shouldContain "read off rather than recorded"
        }
    }

    @Test
    fun `a run nobody staged has no stage table`() {
        ran(staged = false).markdown() shouldNotContain "| Stage "
    }

    @Test
    fun `a goal asked of every stage lands on the row it is about`() {
        val judged = ran(staged = true).let { run ->
            run.copy(plan = run.plan.copy(goals = listOf((p99(step("pay")) under 50.milliseconds).inEveryStage)))
        }

        val summary = judged.markdown()

        summary shouldContain "| Goals "
        withClue("the easy half met it and the slow half did not, which the mixture above hides") {
            summary shouldContain "pay p99 under 50ms: met"
            summary shouldContain "pay p99 under 50ms: missed"
        }
    }

    @Test
    fun `a staged run nobody asked a per-stage goal of keeps the table it had`() {
        val summary = ran(staged = true).markdown()

        summary shouldContain "| Stage "
        summary shouldNotContain "| Goals "
    }
}
