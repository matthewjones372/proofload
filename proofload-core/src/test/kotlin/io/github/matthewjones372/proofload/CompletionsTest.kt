package io.github.matthewjones372.proofload

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val orderId = sessionKey<Long>("orderId")
private val submitted = step("submitted")
private val started = Instant.parse("2026-08-26T09:00:00Z")

class CompletionsTest {

    @Test
    fun `an emit step carries the id the sink will answer with`() {
        val trades = scenario("trades") {
            emit(submitted, action { set(orderId, 7L) }, keyedBy = { session -> session[orderId] ?: 0L })
        }

        val emitted = trades.steps.single().shouldBeInstanceOf<Step.Emit>()
        emitted.name shouldBe "submitted"
        emitted.correlation.of(emitted.action.run(Session.empty).session) shouldBe 7L
    }

    @Test
    fun `a record is timed from the departure the profile promised, not from when it left`() {
        val pending = Pending()
        pending.departed(id = 7L, intended = 1.seconds, at = 1.seconds + 300.milliseconds)

        pending.observed(id = 7L, at = 3.seconds) shouldBe 2.seconds
    }

    @Test
    fun `a sink that answers before the publish returns has a fast pipeline, not a lost record`() {
        val pending = Pending()
        pending.observed(id = 7L, at = 3.seconds).shouldBeNull()

        pending.departed(id = 7L, intended = 1.seconds, at = 2.seconds)

        pending.matched() shouldBe listOf(2.seconds)
        pending.close(at = 10.seconds, window = 5.seconds) shouldBe Outstanding.none
    }

    @Test
    fun `a record the sink never answers for is counted, not left out of the sample`() {
        val pending = Pending()
        pending.departed(id = 7L, intended = 1.seconds, at = 1.seconds)

        pending.close(at = 10.seconds, window = 5.seconds) shouldBe Outstanding(unmatched = 1L, inFlight = 0L)
    }

    @Test
    fun `a record that left too late to be given the window is still moving, not lost`() {
        val pending = Pending()
        pending.departed(id = 7L, intended = 1.seconds, at = 9.seconds)

        pending.close(at = 10.seconds, window = 5.seconds) shouldBe Outstanding(unmatched = 0L, inFlight = 1L)
    }

    @Test
    fun `an id the sink answers with that nobody ever sends matches nothing`() {
        val pending = Pending()

        pending.observed(id = 7L, at = 1.seconds).shouldBeNull()

        pending.matched() shouldBe emptyList()
        pending.close(at = 10.seconds, window = 5.seconds) shouldBe Outstanding.none
    }

    @Test
    fun `an in-memory sink hands over every id it was given, once`() {
        val sink = InMemoryCompletions()
        sink.observe(7L)
        sink.observe(8L)

        sink.poll(within = 1.seconds) shouldBe listOf(7L, 8L)
        sink.poll(within = Duration.ZERO) shouldBe emptyList()
    }

    @Test
    fun `what arrived and what never did reach the result under the step that was waiting`() {
        val recorder = RunRecorder(started)
        recorder.arrived(step = "settled", latency = 2.seconds, at = 3.seconds)
        recorder.outstanding(step = "settled", outstanding = Outstanding(unmatched = 41L, inFlight = 12L))

        val settled = recorder.freeze()["settled"]

        settled.count shouldBe 1L
        settled.unmatched shouldBe 41L
        settled.inFlight shouldBe 12L
        settled.serviceTime.max shouldBe settled.responseTime.max
    }

    @Test
    fun `an answer lands on the timeline in the second it was observed, which is the second it was measured in`() {
        val recorder = RunRecorder(started)
        recorder.arrived(step = "settled", latency = 2.seconds, at = 3.seconds)

        val timeline = recorder.freeze().timeline

        timeline.size shouldBe 4
        timeline[3].count shouldBe 1L
        timeline[0].count shouldBe 0L
    }

    @Test
    fun `two recorders merged add up what each was still waiting for`() {
        val left = RunRecorder(started)
        val right = RunRecorder(started)
        left.outstanding(step = "settled", outstanding = Outstanding(unmatched = 2L, inFlight = 1L))
        right.outstanding(step = "settled", outstanding = Outstanding(unmatched = 3L, inFlight = 4L))

        left.merge(right)

        left.freeze()["settled"].unmatched shouldBe 5L
        left.freeze()["settled"].inFlight shouldBe 5L
    }
}
