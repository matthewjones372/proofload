package io.github.matthewjones372.proofload.java

import io.github.matthewjones372.proofload.Action
import io.github.matthewjones372.proofload.StepName
import io.github.matthewjones372.proofload.scenario
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The facade builds core's own value or it is a second source of truth, and the
 * only way to say so is to build the same scenario both ways and compare.
 */
class ScenarioEqualityTest {

    private val browse = StepName("browse")

    private val noop = Action { }

    @Test
    fun `the Java builder produces the scenario the Kotlin DSL produces`() {
        val fromJava = Scenarios.named("checkout")
            .exec(browse, noop)
            .pause(Duration.ofSeconds(1))
            .build()

        fromJava shouldBe scenario("checkout") {
            exec(browse, noop)
            pause(1.seconds)
        }
    }
}
