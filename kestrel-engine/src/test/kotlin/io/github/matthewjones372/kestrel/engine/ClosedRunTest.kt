package io.github.matthewjones372.kestrel.engine

import io.github.matthewjones372.kestrel.Progress
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.fedBy
import io.github.matthewjones372.kestrel.feed
import io.github.matthewjones372.kestrel.heldScheduleFor
import io.github.matthewjones372.kestrel.lostGround
import io.github.matthewjones372.kestrel.offered
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.sessionKey
import io.github.matthewjones372.kestrel.step
import io.github.matthewjones372.kestrel.users
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Fifty users looping is how most people describe load. What the run must not
 * do is describe itself as having kept a schedule it never promised.
 */
class ClosedRunTest {

    private val browse = step("browse")

    private val user = sessionKey<Long>("user")

    @Test
    fun `a population of fifty runs fifty journeys at once, and goes round again`() {
        val atOnce = AtomicInteger()
        val most = AtomicInteger()
        val looping = scenario("looping") {
            exec(browse) {
                val now = atOnce.incrementAndGet()
                most.getAndUpdate { seen -> maxOf(seen, now) }
                Thread.sleep(20)
                atOnce.decrementAndGet()
            }
        }

        val result = looping.at(users(50, over = 500.milliseconds)).run(Progress.silent)

        withClue("fifty concurrent journeys, not fifty requests") { most.get() shouldBe 50 }
        withClue("each user went round more than once in half a second of twenty-millisecond work") {
            (result[browse].count > 50L) shouldBe true
        }
    }

    @Test
    fun `it reports no lateness, rather than a run that was never late`() {
        val result = scenario("looping") { exec(browse) { Thread.sleep(5) } }
            .at(users(4, over = 300.milliseconds))
            .run(Progress.silent)

        withClue("a generator is not late for a departure nobody promised") {
            // Count rather than identity with `Timing.none`: an empty table
            // still knows how wide it would have been, which 0047 settled.
            result.behind.count shouldBe 0L
            result.latePerSecond shouldBe emptyList()
            result.lostGround() shouldBe false
            result.heldScheduleFor shouldBe null
            result.offered shouldBe null
        }
    }

    @Test
    fun `the two clocks collapse, which is the finding rather than a gap`() {
        val result = scenario("looping") { exec(browse) { Thread.sleep(5) } }
            .at(users(4, over = 300.milliseconds))
            .run(Progress.silent)

        withClue("response time counts from a promised departure, and there is none after the first") {
            result[browse].responseTime.p99 shouldBe result[browse].serviceTime.p99
            result[browse].responseTime.count shouldBe result[browse].serviceTime.count
        }
    }

    @Test
    fun `a looping user gets new data each time round, not the same row for the whole run`() {
        val seen = ConcurrentHashMap.newKeySet<Long>()
        val looping = scenario("looping") {
            exec(browse) {
                this[user]?.let { seen += it }
                Thread.sleep(10)
            }
        }

        val result = looping.at(users(4, over = 300.milliseconds))
            .fedBy(feed(user) { it })
            .run(Progress.silent)

        withClue("$seen") { seen.size shouldBe result[browse].count.toInt() }
    }

    @Test
    fun `the run still says what it achieved, which is the only rate there is`() {
        val result = scenario("looping") { exec(browse) { Thread.sleep(5) } }
            .at(users(4, over = 1.seconds))
            .run(Progress.silent)

        withClue("the target set the rate, and this is it") {
            (result.count > 0L) shouldBe true
            result.timeline.isNotEmpty() shouldBe true
        }
        withClue("and records no arrival spacing: that needs one thread seeing departures in order") {
            result.arrivals.count shouldBe 0L
        }
    }
}
