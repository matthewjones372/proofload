package io.github.matthewjones372.kestrel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Instant

private val browse = step("browse")
private val pay = step("pay")

class StepNameTest {

    private val result = RunResult(
        startedAt = Instant.parse("2026-08-26T09:00:00Z"),
        steps = mapOf("pay" to statsFor("pay")),
        behind = Histogram().timing(),
    )

    private fun statsFor(name: String) = StepStats(
        name = name,
        ok = Outcome.none,
        failed = Outcome.none,
        serviceTime = Histogram().timing(),
        responseTime = Histogram().timing(),
    )

    @Test
    fun `a scenario declared with a handle records the name the handle holds`() {
        val checkout = scenario("checkout") {
            exec(browse) { }
            exec(pay) { }
        }

        checkout.steps.map { it.name } shouldBe listOf("browse", "pay")
    }

    @Test
    fun `the handle that declared a step is the handle that reads it back`() {
        result[pay].name shouldBe "pay"
    }

    @Test
    fun `a handle and the string it holds are the same lookup`() {
        result[pay] shouldBe result["pay"]
    }

    @Test
    fun `a handle for a step that never ran still names the ones that did`() {
        val thrown = shouldThrow<IllegalArgumentException> { result[browse] }

        thrown.message.toString() shouldContain "pay"
    }

    @Test
    fun `two handles of the same name are the same handle`() {
        step("pay") shouldBe pay
    }
}
