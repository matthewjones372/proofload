package io.github.matthewjones372.proofload

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val started = Instant.parse("2026-10-12T09:00:00Z")

/** Spec 0143: a second nothing in flight can land in is held as what it freezes to, not as tables. */
class ClosedSecondsTest {

    private fun RunRecorder.send(step: String, second: Int, index: Int) = record(
        step = step,
        failure = if (index % 7 == 0) Said("status 503") else null,
        serviceTime = (5 + index * 3).milliseconds,
        schedulingDelay = (index % 4).milliseconds,
        at = second.seconds + (index * 90).milliseconds,
    )

    // Quiet seconds, a step that starts late and a failure every seventh
    // request: each is a shape the timeline has to come out of the same.
    private fun RunRecorder.second(second: Int) {
        if (second % 5 == 3) return
        repeat(8) { send("browse", second, it) }
        if (second >= 12) repeat(3) { send("pay", second, it) }
    }

    @Test
    fun `a closed second holds no tables, and the seconds still open do`() {
        val recorder = RunRecorder(started)
        (0..9).forEach { recorder.second(it) }

        recorder.close(before = 9.seconds)

        withClue("second 9 is still open, for browse and for lateness") {
            recorder.tablesHeld shouldBe 2
        }
    }

    @Test
    fun `closing seconds as a run goes freezes into the result an unclosed recorder does`() {
        val closing = RunRecorder(started)
        val kept = RunRecorder(started)

        (0 until 30).forEach { second ->
            closing.second(second)
            kept.second(second)
            closing.close(before = second.seconds)
        }

        closing.freeze() shouldBe kept.freeze()
    }

    @Test
    fun `a record for a second already closed is still counted there`() {
        val closing = RunRecorder(started)
        val kept = RunRecorder(started)
        (0 until 10).forEach { closing.second(it); kept.second(it) }
        closing.close(before = 10.seconds)

        closing.send("browse", 4, 1)
        kept.send("browse", 4, 1)

        closing.freeze() shouldBe kept.freeze()
        closing.close(before = 10.seconds)
        withClue("the late record is folded in at the next close") { closing.tablesHeld shouldBe 0 }
        closing.freeze() shouldBe kept.freeze()
    }

    @Test
    fun `shards closed at different points merge into what one unclosed recorder says`() {
        val root = RunRecorder(started)
        val early = root.shard()
        val late = root.shard()
        val kept = RunRecorder(started)

        (0 until 20).forEach { second ->
            (if (second % 2 == 0) early else late).second(second)
            kept.second(second)
        }
        early.close(before = 15.seconds)
        late.close(before = 4.seconds)
        val merged = root.shard().apply { merge(late); merge(early) }

        merged.freeze() shouldBe kept.freeze()
    }
}
