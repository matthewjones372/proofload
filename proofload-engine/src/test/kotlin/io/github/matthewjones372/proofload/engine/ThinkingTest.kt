package io.github.matthewjones372.proofload.engine

import io.github.matthewjones372.proofload.Progress
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.exponential
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.scenario
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What the engine does about a drawn wait, without measuring one.
 *
 * That the draws are reproducible from a seed is `ThinkTimeTest`'s, with no
 * clock; that the engine really parks for them is `DrawnPausesTest`'s in
 * `examples`, tagged and run alone, because a wall-clock assertion inside
 * `./gradlew build` measures the build.
 */
class ThinkingTest {

    @Test
    fun `a scenario that draws its waits without a seed is refused before anything departs`() {
        val thinking = scenario("thinking") {
            exec("browse") { }
            pause(exponential(mean = 50.milliseconds))
        }

        val why = shouldThrow<IllegalArgumentException> {
            thinking.at(5.perSecond, over = 1.seconds).run(Progress.silent)
        }

        why.message.orEmpty() shouldContain "draws its think time and has no seed"
    }

    @Test
    fun `a scenario of constant pauses still runs with no seed at all`() {
        val thinking = scenario("thinking") {
            exec("browse") { }
            pause(20.milliseconds)
        }

        val result = thinking.at(5.perSecond, over = 1.seconds).run(Progress.silent)

        result["browse"].count shouldBe 5L
    }
}
