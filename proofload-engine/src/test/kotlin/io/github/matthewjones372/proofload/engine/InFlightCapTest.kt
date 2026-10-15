package io.github.matthewjones372.proofload.engine

import io.github.matthewjones372.proofload.Progress
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.fedBy
import io.github.matthewjones372.proofload.feed
import io.github.matthewjones372.proofload.inFlightAtMost
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.sessionKey
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val user = sessionKey<Long>("user")

/**
 * The gate is a latch opened once the schedule's window has passed, so every
 * arrival falls due while the first ones are still held: the counts are exact
 * however fast the machine is.
 */
class InFlightCapTest {

    private val released = CountDownLatch(1)

    // Opens on the first tick past the window, which is after the last arrival was due.
    private val releasingAfterTheWindow = Progress { elapsed, snapshot ->
        if (elapsed > snapshot.scheduled + 500.milliseconds) released.countDown()
    }

    private val held = scenario("held") { exec("wait") { released.await() } }

    @Test
    fun `an arrival due while the cap is in flight is refused and sends nothing`() {
        val result = held.at(100.perSecond, over = 1.seconds).inFlightAtMost(10).run(releasingAfterTheWindow)

        withClue("ten were started and held; the other ninety fell due while they were") {
            result["wait"].count shouldBe 10L
            result.refused shouldBe 90L
        }
        withClue("a refusal is the generator's, not the target's") {
            result.failed shouldBe 0L
        }
    }

    @Test
    fun `refusals are counted in the slot their arrival was due in`() {
        val result = held.at(100.perSecond, over = 1.seconds).inFlightAtMost(10).run(releasingAfterTheWindow)

        result.refusedPerSecond.first() shouldBe 90L
    }

    @Test
    fun `a refused user keeps its number, so the users that ran had their own data`() {
        val seen = ConcurrentLinkedQueue<Long>()
        val numbered = scenario("numbered") {
            exec("wait") {
                get(user)?.let(seen::add)
                released.await()
            }
        }

        numbered.at(100.perSecond, over = 1.seconds)
            .fedBy(feed(user) { it })
            .inFlightAtMost(3)
            .run(releasingAfterTheWindow)

        seen.toList() shouldContainExactlyInAnyOrder listOf(0L, 1L, 2L)
    }

    @Test
    fun `a run with no cap refuses nothing`() {
        val quick = scenario("quick") { exec("touch") { } }

        val result = quick.at(50.perSecond, over = 1.seconds).run(Progress.silent)

        result.refused shouldBe 0L
        result.refusedPerSecond shouldBe emptyList()
    }

    @Test
    fun `users that finish free their place for the arrivals after them`() {
        val quick = scenario("quick") { exec("touch") { } }

        val result = quick.at(50.perSecond, over = 1.seconds).inFlightAtMost(5).run(Progress.silent)

        withClue("a step that returns at once never holds five places") {
            result.refused shouldBe 0L
            result["touch"].count shouldBe 50L
        }
    }
}
