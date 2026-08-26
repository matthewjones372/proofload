package io.github.matthewjones372.kestrel

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class SimulationTest {

    private val checkout = scenario("checkout") { exec("browse") { } }

    @Test
    fun `a rate reads as a sentence and means the same either way it is spelled`() {
        60.perMinute shouldBe 1.perSecond
        0.5.perSecond shouldBe 30.perMinute
    }

    @Test
    fun `a scenario reaches a simulation in one call`() {
        val simulation = checkout.at(50.perSecond, over = 1.minutes)

        simulation shouldBe Simulation(checkout, constantRate(50.perSecond, over = 1.minutes))
    }

    @Test
    fun `a simulation can be handed any profile, not just a constant rate`() {
        val ramp = rampRate(from = 1.perSecond, to = 20.perSecond, over = 10.seconds)

        checkout.injecting(ramp) shouldBe Simulation(checkout, ramp)
    }

    @Test
    fun `a simulation says how many users it will send before it sends any`() {
        checkout.at(50.perSecond, over = 1.minutes).profile.userCount() shouldBe 3000L
    }
}
