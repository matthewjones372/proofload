package io.github.matthewjones372.kestrel.engine

import io.github.matthewjones372.kestrel.Progress
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.exponential
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.thinkingFrom
import io.github.matthewjones372.kestrel.uniform
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A drawn wait has to be reproducible or it is not an experiment. It also has
 * to be the user's own, so what user 4,001 waited does not depend on how fast
 * the target answered the users before it.
 */
class ThinkingTest {

    /**
     * How long each user actually parked, in whole milliseconds and sorted.
     *
     * Measured off the run's own clock, so each reading carries a millisecond
     * or so of scheduling noise on top of the draw — which is why these are
     * compared with a tolerance rather than for equality, and sorted rather
     * than kept in the order the users happened to finish in.
     */
    private fun waitsOf(seed: Long): List<Long> {
        val waited = ConcurrentLinkedQueue<Long>()
        val timed = scenario("timed") {
            exec("start") { set(startedAt, System.nanoTime()) }
            pause(uniform(from = 1.milliseconds, until = 200.milliseconds))
            exec("end") {
                this[startedAt]?.let { waited += (System.nanoTime() - it) / NANOS_PER_MILLI }
            }
        }

        timed.at(5.perSecond, over = 1.seconds).thinkingFrom(seed).run(Progress.silent)
        return waited.sorted()
    }

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

    @Test
    fun `two runs at one seed park their users for the same durations`() {
        val once = waitsOf(seed = 38)
        val again = waitsOf(seed = 38)

        withClue("$once against $again") {
            once.size shouldBe again.size
            once.zip(again).all { (first, second) -> kotlin.math.abs(first - second) <= SLACK } shouldBe true
        }
    }

    @Test
    fun `two runs at different seeds park them differently`() {
        val once = waitsOf(seed = 38)
        val other = waitsOf(seed = 39)

        withClue("$once against $other") {
            once.zip(other).any { (first, second) -> kotlin.math.abs(first - second) > SLACK } shouldBe true
        }
    }

    @Test
    fun `users of one run do not all wait the same`() {
        val waits = waitsOf(seed = 38)

        withClue("a constant pause would give one wait repeated: $waits") {
            (waits.distinct().size > 1) shouldBe true
        }
    }

    private companion object {
        val startedAt = io.github.matthewjones372.kestrel.sessionKey<Long>("startedAt")

        const val NANOS_PER_MILLI = 1_000_000L

        /** What a scheduled wake-up costs on top of the draw, on a loaded machine. */
        const val SLACK = 15L
    }
}
