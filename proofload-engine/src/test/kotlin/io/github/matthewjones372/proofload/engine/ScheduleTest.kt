package io.github.matthewjones372.proofload.engine

import io.github.matthewjones372.proofload.Arm
import io.github.matthewjones372.proofload.Rate
import io.github.matthewjones372.proofload.constantRate
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.scenario
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeSorted
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ScheduleTest {

    private fun arm(name: String, rate: Rate, over: Duration = 1.seconds) =
        Arm(scenario(name) { exec(name) { } }, constantRate(rate, over))

    private val browse = arm("browse", 2.perSecond)
    private val search = arm("search", 1.perSecond)

    @Test
    fun `a one-armed run departs exactly as the profile named`() {
        listOf(browse).schedule().map { it.offset }.toList() shouldBe listOf(Duration.ZERO, 500.milliseconds)
    }

    @Test
    fun `two arms depart in one schedule, earliest first`() {
        val schedule = listOf(browse, search).schedule().toList()

        schedule.map { it.offset } shouldBe listOf(Duration.ZERO, Duration.ZERO, 500.milliseconds)
        withClue("an arrival recorder handed a gap that ran backwards would report a spacing nobody offered") {
            schedule.map { it.offset }.shouldBeSorted()
        }
    }

    @Test
    fun `two arms due at the same moment leave in the order the mix names them`() {
        listOf(browse, search).schedule().first().arm shouldBe browse
        listOf(search, browse).schedule().first().arm shouldBe search
    }

    @Test
    fun `each arm numbers its own users from zero, whatever the other arms are sending`() {
        val schedule = listOf(browse, search).schedule().toList()

        schedule.filter { it.arm == browse }.map { it.user } shouldBe listOf(0L, 1L)
        schedule.filter { it.arm == search }.map { it.user } shouldBe listOf(0L)
    }

    @Test
    fun `a schedule is read as it goes, so a mix nobody could hold in memory still departs`() {
        val busy = listOf(arm("browse", 400_000.perSecond, over = 10.minutes), arm("search", 80_000.perSecond))

        busy.schedule().take(3).map { it.arm.scenario.name }.toList() shouldBe listOf("browse", "search", "browse")
    }
}
