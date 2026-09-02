package io.github.matthewjones372.kestrel.engine

import io.github.matthewjones372.kestrel.Progress
import io.github.matthewjones372.kestrel.Said
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.scenario
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A step that observes several answers records several. A stream of a hundred
 * messages measured as one sample is a number describing the batch, and the
 * distribution of the messages inside it is lost.
 */
class SampleTest {

    @Test
    fun `three samples from one body are one row of three, reached once`() {
        val streaming = scenario("streaming") {
            exec("tick") {
                sample(10.milliseconds)
                sample(20.milliseconds)
                sample(30.milliseconds)
            }
        }

        val result = streaming.at(1.perSecond, over = 1.seconds).run(Progress.silent)

        val tick = result["tick"]
        withClue("one user, three answers") {
            tick.count shouldBe 3L
            tick.reached shouldBe 1L
        }
        withClue("the body's own durations, not the body's own wall clock") {
            tick.serviceTime.max.inWholeMilliseconds shouldBe 30L
        }
    }

    @Test
    fun `a body that reports nothing is still one sample for the whole step`() {
        val ordinary = scenario("ordinary") { exec("touch") { } }

        val result = ordinary.at(5.perSecond, over = 1.seconds).run(Progress.silent)

        result["touch"].count shouldBe 5L
        result["touch"].reached shouldBe 5L
    }

    @Test
    fun `a sample can fail on its own without failing the step`() {
        val mixed = scenario("mixed") {
            exec("tick") {
                sample(10.milliseconds)
                sample(20.milliseconds, reason = Said("dropped"))
            }
            exec("after") { }
        }

        val result = mixed.at(1.perSecond, over = 1.seconds).run(Progress.silent)

        result["tick"].count shouldBe 2L
        result["tick"].failed.count shouldBe 1L
        result["tick"].failedWith(Said("dropped")) shouldBe 1L
        withClue("the step did not fail, so the user was not abandoned") {
            result["after"].count shouldBe 1L
        }
    }

    @Test
    fun `each sample lands in the second it was observed in`() {
        val overTwo = scenario("over two") {
            exec("tick") {
                sample(1.milliseconds, at = 0.seconds)
                sample(1.milliseconds, at = 2.seconds)
            }
        }

        val result = overTwo.at(1.perSecond, over = 1.seconds).run(Progress.silent)

        result.timeline[0].count shouldBe 1L
        result.timeline[2].count shouldBe 1L
    }
}
