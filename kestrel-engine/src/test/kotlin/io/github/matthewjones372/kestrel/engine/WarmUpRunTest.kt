package io.github.matthewjones372.kestrel.engine

import io.github.matthewjones372.kestrel.Progress
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.warmingUp
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/**
 * A warm-up is load the target sees and the measurement does not: the point is
 * that a cold JVM's first departures are paid for outside the numbers the run
 * reports.
 */
class WarmUpRunTest {

    @Test
    fun `the target is hit while warming, and none of it reaches the result`() {
        val hits = AtomicLong()
        val counting = scenario("counting") { exec("touch") { hits.incrementAndGet() } }

        val warmed = counting.at(20.perSecond, over = 1.seconds).warmingUp(1.seconds)
        val result = warmed.run(Progress.silent)

        withClue("the warm-up offered its own users at the rate the shape opens at") {
            hits.get() shouldBeGreaterThanOrEqualTo 30L
        }
        withClue("but the result counts only the measured window") {
            result["touch"].count shouldBe 20L
            result.timeline.size shouldBeGreaterThanOrEqualTo 1
        }
    }

    @Test
    fun `a run that warms nothing is unchanged`() {
        val hits = AtomicLong()
        val counting = scenario("counting") { exec("touch") { hits.incrementAndGet() } }

        val result = counting.at(20.perSecond, over = 1.seconds).run(Progress.silent)

        hits.get() shouldBe 20L
        result["touch"].count shouldBe 20L
    }
}
