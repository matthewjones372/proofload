package io.github.matthewjones372.proofload.examples

import io.github.matthewjones372.proofload.Progress
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.engine.run
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.sessionKey
import io.github.matthewjones372.proofload.thinkingFrom
import io.github.matthewjones372.proofload.uniform
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * That the engine parks a user for the length it drew, on a wall clock.
 *
 * `ThinkTimeTest` in core already proves the draws themselves are reproducible
 * from a seed, and does it with no clock at all. What is left to prove is that
 * the engine feeds the seed through and really waits — which can only be seen
 * by timing it, so it is tagged and runs alone.
 *
 * It lived in `proofload-engine` and flaked there, exactly as 0041 predicts: two
 * runs at one seed drew the same durations and were *delivered* 34 ms apart
 * under a parallel build, because a wall-clock test inside `./gradlew build`
 * measures the build.
 */
@Tag("timing")
class DrawnPausesTest {

    private val startedAt = sessionKey<Long>("startedAt")

    /** How long each user actually parked, in whole milliseconds and sorted. */
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
        // Sorted rather than in the order users finished: what is being
        // compared is the set of durations the seed produced, and a user that
        // drew a short wait overtakes one that drew a long one.
        return waited.sorted()
    }

    @Test
    fun `the engine parks users for the durations the seed drew, run after run`() {
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
        const val NANOS_PER_MILLI = 1_000_000L

        /** What a scheduled wake-up costs on top of the draw, on a machine running this alone. */
        const val SLACK = 15L
    }
}
