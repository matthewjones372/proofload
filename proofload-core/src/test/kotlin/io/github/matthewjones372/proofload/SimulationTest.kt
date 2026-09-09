package io.github.matthewjones372.proofload

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class SimulationTest {

    private val checkout = scenario("checkout") { exec("browse") { } }

    private val search = scenario("search") { exec("query") { } }

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

    @Test
    fun `a one-armed simulation is one arm`() {
        checkout.at(50.perSecond, over = 1.minutes).arms shouldBe
            listOf(Arm(checkout, constantRate(50.perSecond, over = 1.minutes)))
    }

    @Test
    fun `two scenarios in one run each keep their own rate line`() {
        val mixed = checkout.at(50.perSecond, over = 1.minutes) + search.at(8.perSecond, over = 2.minutes)

        mixed.arms shouldBe listOf(
            Arm(checkout, constantRate(50.perSecond, over = 1.minutes)),
            Arm(search, constantRate(8.perSecond, over = 2.minutes)),
        )
    }

    @Test
    fun `a mix says how many users it will send and how long it lasts before it sends any`() {
        val mixed = checkout.at(50.perSecond, over = 1.minutes) + search.at(8.perSecond, over = 2.minutes)

        mixed.userCount() shouldBe 3960L
        mixed.over shouldBe 2.minutes
    }

    @Test
    fun `two arms sharing a step name are refused, naming the step and both scenarios`() {
        val rival = scenario("rival") { exec("browse") { } }

        val refusal = shouldThrow<IllegalArgumentException> {
            checkout.at(50.perSecond, over = 1.minutes) + rival.at(8.perSecond, over = 1.minutes)
        }

        refusal.message shouldContain "browse"
        refusal.message shouldContain "checkout"
        refusal.message shouldContain "rival"
    }

    @Test
    fun `a run says what its data was drawn from, and every arm carries it`() {
        val shapes = arrayOf(Shape("zipf(keys=1000000, skew=1.1)", seed = 4L), Shape("uuids()", seed = 5L))
        val mixed = checkout.at(50.perSecond, over = 1.minutes) + search.at(8.perSecond, over = 2.minutes)

        mixed.drawing(*shapes).arms.map { it.drawn } shouldBe listOf(shapes.toList(), shapes.toList())
    }

    @Test
    fun `a run nobody told what it drew from claims nothing`() {
        checkout.at(50.perSecond, over = 1.minutes).arms.single().drawn.shouldBeEmpty()
    }
}
