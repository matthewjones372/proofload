package io.github.matthewjones372.proofload.engine

import io.github.matthewjones372.proofload.Progress
import io.github.matthewjones372.proofload.Said
import io.github.matthewjones372.proofload.Snapshot
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.scenario
import io.kotest.assertions.withClue
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.seconds

/**
 * A watcher can say how much has been recorded, and what it says at the end is
 * what the frozen result says — otherwise the line is a guess with a number in
 * it.
 */
class ProgressCountsTest {

    @Test
    fun `the last tick's counts are the counts the result froze`() {
        val ticks = ConcurrentLinkedQueue<Snapshot>()
        val watching = Progress { _, snapshot -> ticks += snapshot }
        val everyThird = scenario("counting") {
            exec("touch") { if (System.nanoTime() % 3L == 0L) fail(Said("unlucky")) }
        }

        val result = everyThird.at(40.perSecond, over = 2.seconds).run(watching)

        val last = ticks.last()
        withClue("the final tick is taken after the last record, so it is the frozen run") {
            last.requests shouldBe result.count
            last.failed shouldBe result.failed
        }
    }

    @Test
    fun `a run counts what it recorded rather than what it departed`() {
        val ticks = ConcurrentLinkedQueue<Snapshot>()
        val watching = Progress { _, snapshot -> ticks += snapshot }
        val twoSteps = scenario("two") {
            exec("one") { }
            exec("two") { }
        }

        val result = twoSteps.at(20.perSecond, over = 1.seconds).run(watching)

        withClue("two steps a user, so requests outnumber departures") {
            ticks.last().requests shouldBe result.count
            result.count shouldBeGreaterThan ticks.last().departed
        }
    }
}
