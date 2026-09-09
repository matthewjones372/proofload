package io.github.matthewjones372.proofload.engine

import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.scenario
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ScheduleDriftTest {

    private val nothing = scenario("nothing") { exec("step") { } }

    /**
     * `schedule` takes a delay from now, and a departure is an offset from the
     * run's start. Booking is not instant, so a run that hands the offset over
     * unadjusted starts every user late by however long booking took — the
     * drift the profile computes its offsets from an index to avoid.
     */

    /**
     * On the median, not the tail. Drift moved every departure, so it showed up
     * in the middle of the distribution; a p99 gate would instead be measuring
     * whatever else the machine was doing, which during a full build is seven
     * other test JVMs.
     */
    @Test
    fun `booking a run does not make its own departures late`() {
        val result = nothing.at(2_000.perSecond, over = 2.seconds).run()

        withClue("behind p50 was ${result.behind.p50}, p99 ${result.behind.p99}") {
            (result.behind.p50 < 25.milliseconds) shouldBe true
        }
    }

    @Test
    fun `a later user is no later than an earlier one, so lateness does not accumulate`() {
        val result = nothing.at(1_000.perSecond, over = 2.seconds).run()

        withClue("p50 ${result.behind.p50}, p99 ${result.behind.p99}") {
            (result.behind.p50 < 25.milliseconds) shouldBe true
        }
    }
}
