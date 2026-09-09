package io.github.matthewjones372.proofload.report

import io.github.matthewjones372.proofload.Capacity
import io.github.matthewjones372.proofload.Histogram
import io.github.matthewjones372.proofload.Plan
import io.github.matthewjones372.proofload.Rung
import io.github.matthewjones372.proofload.Second
import io.github.matthewjones372.proofload.Timing
import io.github.matthewjones372.proofload.hold
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.timing
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * "Behind schedule" on its own is a diagnosis with no treatment. What a reader
 * needs beside it is the load that actually left and how long the schedule
 * held, because the service times on the page are still true about that load.
 */
class WhatLeftTest {

    /** The behind fixture, given a plan it fell short of and seconds it recorded. */
    private fun behindByFive() = Fixtures.fellBehind.copy(
        plan = Plan("checkout", listOf("browse", "pay"), hold(10.perSecond, over = 10.seconds)),
        timeline = List(15) { second(count = 10) },
        latePerSecond = List(11) { Timing.none } + List(4) { late() },
    )

    @Test
    fun `the warning says what left, over how long, and how long the schedule held`() {
        val page = behindByFive().toHtmlReport()

        page shouldContain "Asked for"
        page shouldContain "left over 15.0 s"
        page shouldContain "The schedule held for 11.0 s."
        page shouldContain "Service times below are the target at that load."
    }

    @Test
    fun `a run that kept its schedule says none of it`() {
        Fixtures.keptSchedule.toHtmlReport() shouldNotContain "Service times below are the target at that load."
    }

    @Test
    fun `a run that fell behind but planned nothing says only what it can`() {
        val page = Fixtures.fellBehind.toHtmlReport()

        page shouldContain "Behind schedule."
        page shouldNotContain "Asked for"
    }

    @Test
    fun `the timeline draws when the lateness went, and draws nothing where there was none`() {
        behindByFive().toHtmlReport() shouldContain "how late departures were"
        Fixtures.fellBehind.toHtmlReport() shouldNotContain "how late departures were"
    }

    @Test
    fun `a void rung names the load that left and what the target did at it`() {
        val void = Rung(
            rate = 100.perSecond,
            result = Fixtures.fellBehind.copy(
                plan = Plan("checkout", listOf("browse", "pay"), hold(100.perSecond, over = 10.seconds)),
            ),
        )

        val page = Capacity(listOf(void)).toHtmlReport()

        page shouldContain "not judged — the injector lost the schedule"
        page shouldContain "left, service p99"
    }

    private fun second(count: Int) = Second(
        okServiceTime = timingOf(List(count) { 20.milliseconds }),
        failedServiceTime = Timing.none,
        okResponseTime = timingOf(List(count) { 40.milliseconds }),
        failedResponseTime = Timing.none,
    )

    private fun late(): Timing = timingOf(List(10) { 2.seconds })

    private fun timingOf(values: List<Duration>): Timing =
        Histogram().apply { values.forEach { record(it) } }.timing()
}
