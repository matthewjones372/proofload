package io.github.matthewjones372.kestrel.engine

import io.github.matthewjones372.kestrel.Progress
import io.github.matthewjones372.kestrel.Snapshot
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.scenario
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.seconds

/**
 * A user is in flight once it has left, not once it has been booked. The pump
 * books a window ahead, so counting at booking reported the booking window as
 * concurrency and had nothing to do with the target.
 */
class InFlightTest {

    @Test
    fun `in flight is the users that are running, not the window that has been booked`() {
        val ticks = ConcurrentLinkedQueue<Snapshot>()
        val watching = Progress { _, snapshot -> ticks += snapshot }
        // Ten a second against a step taking 100 ms: about one user is running
        // at a time. Booked ahead, it would be the five-second window's worth.
        val slow = scenario("slow") { exec("wait") { Thread.sleep(100) } }

        slow.at(10.perSecond, over = 2.seconds).run(watching)

        val peak = ticks.maxOfOrNull { it.inFlight } ?: 0L
        withClue("rate times latency is about one; the booking window would be about fifty") {
            (peak <= 10L) shouldBe true
        }
    }

    @Test
    fun `every user still runs, so the run waits for all of them`() {
        val counted = ConcurrentLinkedQueue<Int>()
        val slow = scenario("slow") { exec("wait") { Thread.sleep(20); counted += 1 } }

        val result = slow.at(20.perSecond, over = 2.seconds).run(Progress.silent)

        withClue("awaitAll must not fire while a booked user has yet to start") {
            counted.size shouldBe 40
            result["wait"].count shouldBe 40L
        }
    }

    @Test
    fun `a run reports nothing in flight once it has ended`() {
        val ticks = ConcurrentLinkedQueue<Snapshot>()
        val watching = Progress { _, snapshot -> ticks += snapshot }
        val quick = scenario("quick") { exec("touch") { } }

        quick.at(20.perSecond, over = 1.seconds).run(watching)

        ticks.last().inFlight shouldBe 0L
    }
}
