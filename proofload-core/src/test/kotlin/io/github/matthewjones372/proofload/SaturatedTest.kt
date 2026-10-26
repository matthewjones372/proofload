package io.github.matthewjones372.proofload

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/** Spec 0144: a run whose generator refused arrivals measured the generator, and says so. */
class SaturatedTest {

    private fun resultWith(
        refused: List<Long> = emptyList(),
        afterStall: List<Long> = emptyList(),
        limits: Limits = Limits.none,
    ) = RunResult(
        startedAt = Instant.parse("2026-10-15T09:00:00Z"),
        steps = emptyMap(),
        behind = Timing.none,
        limits = limits,
        refusedPerSecond = refused,
        refusedAfterStallPerSecond = afterStall,
    )

    @Test
    fun `one refusal is enough to call the generator saturated`() {
        resultWith(refused = listOf(0L, 1L)).generatorSaturated() shouldBe true
    }

    @Test
    fun `refusals the generator's own stall caused do not call it saturated`() {
        resultWith(refused = listOf(1_400L, 0L), afterStall = listOf(1_400L, 0L)).generatorSaturated() shouldBe false
    }

    @Test
    fun `one refusal beyond the stalled ones is still saturation`() {
        resultWith(refused = listOf(1_400L, 1L), afterStall = listOf(1_400L, 0L)).generatorSaturated() shouldBe true
    }

    @Test
    fun `a rung whose only refusals followed a stall is judged on its goals`() {
        val stalled = Rung(rate = 100.perSecond, result = resultWith(refused = listOf(9L), afterStall = listOf(9L)))

        stalled.outcome shouldBe Rung.Outcome.Passed
    }

    @Test
    fun `a rung's offered rate leaves out the arrivals it refused`() {
        val plan = Plan(scenario = "pay", steps = listOf("pay"), profile = hold(100.perSecond, over = 10.seconds))
        val stalled = Rung(
            rate = 100.perSecond,
            result = resultWith(refused = listOf(50L), afterStall = listOf(50L)).copy(plan = plan),
        )

        stalled.offered.perSecond shouldBe 95.0
    }

    @Test
    fun `a run that refused nothing and had room is not saturated`() {
        resultWith().generatorSaturated() shouldBe false
    }

    @Test
    fun `running out of room is saturation too`() {
        resultWith(limits = Limits(ports = Headroom.Measured(peak = 27_998, limit = 28_232)))
            .generatorSaturated() shouldBe true
    }

    @Test
    fun `a rung that refused arrivals is void, and the line says how many`() {
        val refusing = Rung(rate = 100.perSecond, result = resultWith(refused = listOf(1_200L, 3L)))

        refusing.outcome shouldBe Rung.Outcome.Void
        printed { Progress.lines().climbed(refusing, number = 1, atMost = 1.seconds) } shouldContain
            "the generator refused 1,203 arrivals at its cap"
    }

    @Test
    fun `the schedule's remedy names the refusals before anything else`() {
        resultWith(refused = listOf(7L)).scheduleRemedy?.shouldContain("refused 7 arrivals")
            ?: error("a run that refused arrivals has a remedy")
    }

    private fun printed(block: () -> Unit): String {
        val out = ByteArrayOutputStream()
        val before = System.out
        System.setOut(PrintStream(out))
        try {
            block()
        } finally {
            System.setOut(before)
        }
        return out.toString()
    }
}
