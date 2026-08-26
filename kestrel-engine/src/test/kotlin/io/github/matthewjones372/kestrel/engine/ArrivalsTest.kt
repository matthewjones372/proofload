package io.github.matthewjones372.kestrel.engine

import io.github.matthewjones372.kestrel.hold
import io.github.matthewjones372.kestrel.injecting
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.randomized
import io.github.matthewjones372.kestrel.scenario
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ArrivalsTest {

    private val nothing = scenario("nothing") { exec("step") { } }

    @Test
    fun `an even run reports the spacing it departed on`() {
        val result = nothing.injecting(hold(200.perSecond, over = 2.seconds)).run()

        result.arrivals.count shouldBe 400L
        result.arrivals.mean shouldBe 5.milliseconds
        withClue("an even run varied by ${result.arrivals.cov}") { (result.arrivals.cov < 0.001) shouldBe true }
    }

    @Test
    fun `a randomised run reports the variation it actually produced`() {
        val profile = hold(200.perSecond, over = 2.seconds).randomized(seed = 20260826)

        val result = nothing.injecting(profile).run()

        result.arrivals.count shouldBe 400L
        withClue("a randomised run varied by ${result.arrivals.cov}") {
            (result.arrivals.cov > 0.8 && result.arrivals.cov < 1.2) shouldBe true
        }
    }

    @Test
    fun `a run that departed nobody has no arrivals to describe`() {
        val result = nothing.injecting(hold(0.perSecond, over = 1.seconds)).run()

        result.arrivals.count shouldBe 0L
    }
}
