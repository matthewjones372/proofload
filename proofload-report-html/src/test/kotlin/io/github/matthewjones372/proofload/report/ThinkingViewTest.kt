package io.github.matthewjones372.proofload.report

import io.github.matthewjones372.proofload.Plan
import io.github.matthewjones372.proofload.PlannedArm
import io.github.matthewjones372.proofload.ThinkTime
import io.github.matthewjones372.proofload.hold
import io.github.matthewjones372.proofload.perSecond
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * A constant pause is a choice whose consequence — every user that met it
 * leaving it in the same instant — is invisible unless the page names it.
 */
class ThinkingViewTest {

    private fun pageWith(think: ThinkTime, seed: Long?) = Fixtures.fellBehind.copy(
        plan = Plan(
            listOf(
                PlannedArm(
                    scenario = "checkout",
                    steps = listOf("browse", "pay"),
                    profile = hold(120.perSecond, over = 4.seconds),
                    pauses = true,
                    thinkTimes = listOf(think),
                    thinkSeed = seed,
                ),
            ),
        ),
    ).toHtmlReport()

    @Test
    fun `a drawn wait names its distribution and the seed it came from`() {
        val page = pageWith(ThinkTime.Exponential(2.seconds), seed = 20260826)

        page shouldContain "Think time: exponential, mean 2.00 s"
        page shouldContain "Drawn from seed 20260826."
    }

    @Test
    fun `a constant wait says every user waited exactly that long`() {
        val page = pageWith(ThinkTime.Constant(2.seconds), seed = null)

        page shouldContain "Think time: a fixed 2.00 s"
        page shouldContain "users that arrive together click again together"
    }

    @Test
    fun `a scenario with no pause says nothing about think time`() {
        Fixtures.fellBehind.toHtmlReport() shouldNotContain "Think time"
    }
}
