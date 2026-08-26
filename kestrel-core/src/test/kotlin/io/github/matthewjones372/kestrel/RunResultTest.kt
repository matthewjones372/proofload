package io.github.matthewjones372.kestrel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class RunResultTest {

    private fun timingOf(values: List<Duration>): Timing =
        Histogram().apply { values.forEach { record(it) } }.timing()

    private val pay = StepStats(
        name = "pay",
        count = 100L,
        ok = 97L,
        failures = mapOf("status 503" to 3L),
        serviceTime = timingOf(listOf(10.milliseconds, 20.milliseconds, 30.milliseconds)),
        responseTime = timingOf(listOf(110.milliseconds, 120.milliseconds, 130.milliseconds)),
    )

    private val result = RunResult(
        startedAt = Instant.parse("2026-08-26T09:00:00Z"),
        steps = mapOf("pay" to pay),
        behind = timingOf(listOf(100.milliseconds)),
    )

    @Test
    fun `a step is read by the name the scenario gave it, not by a URL`() {
        result["pay"] shouldBe pay
    }

    @Test
    fun `asking for a step that never ran names the ones that did`() {
        val thrown = shouldThrow<IllegalArgumentException> { result["chekout"] }

        thrown.message.toString() shouldContain "pay"
    }

    @Test
    fun `a step counts what failed without being told twice`() {
        result["pay"].failed shouldBe 3L
        result["pay"].failures["status 503"] shouldBe 3L
    }

    @Test
    fun `the gap between service time and response time is the generator's backlog`() {
        val gap = result["pay"].responseTime.p99 - result["pay"].serviceTime.p99

        (gap >= 90.milliseconds) shouldBe true
        (result.behind.max >= 100.milliseconds) shouldBe true
    }

    @Test
    fun `a run totals its steps so a summary line does not have to`() {
        val browse = pay.copy(name = "browse", count = 10L, ok = 10L, failures = emptyMap())
        val both = result.copy(steps = mapOf("pay" to pay, "browse" to browse))

        both.count shouldBe 110L
        both.ok shouldBe 107L
        both.failed shouldBe 3L
    }

    @Test
    fun `a run is behind only when its backlog could move a number the report prints`() {
        val negligible = result.copy(behind = timingOf(listOf(1.milliseconds)))
        val serious = result.copy(behind = timingOf(listOf(90.milliseconds)))

        negligible.fellBehind() shouldBe false
        serious.fellBehind() shouldBe true
    }

    @Test
    fun `a run with no steps cannot be behind, because there is nothing to be late for`() {
        result.copy(steps = emptyMap()).fellBehind() shouldBe false
    }

    @Test
    fun `a timing over nothing reports nothing rather than a zero somebody reads as fast`() {
        val empty = Histogram().timing()

        empty.count shouldBe 0L
        empty.p99 shouldBe Duration.ZERO
    }
}
