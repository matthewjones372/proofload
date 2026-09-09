package io.github.matthewjones372.proofload.engine

import io.github.matthewjones372.proofload.Progress
import io.github.matthewjones372.proofload.Shard
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.fedBy
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.plan
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.sharded
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Four injectors have to offer exactly the departures one JVM would, at the
 * same offsets, each user sent once. Anything else is four experiments that
 * cannot honestly be pooled.
 */
class ShardTest {

    /** A fixed instant, for the checks that build a Shard rather than run one. */
    private val whenever = Instant.parse("2026-08-26T12:00:00Z")

    /** Soon enough not to slow a test, far enough that a run reaches it. */
    private fun shortly() = Instant.now().plusMillis(200)

    /** Which users a run recorded, by the value each wrote from its own feeder. */
    private fun usersSentBy(index: Int?, of: Int = 4): List<Long> {
        val sent = ConcurrentLinkedQueue<Long>()
        val counting = scenario("counting") { exec("touch") { sent += this[user] ?: -1L } }
        val run = counting.at(20.perSecond, over = 1.seconds)
            .fedBy(io.github.matthewjones372.proofload.feed(user) { it })
            .let { if (index == null) it else it.sharded(index, of, shortly()) }

        run.run(Progress.silent)
        return sent.sorted()
    }

    @Test
    fun `four shards together send exactly what one JVM sends, each user once`() {
        val whole = usersSentBy(index = null)

        val shared = (0 until 4).flatMap { usersSentBy(index = it) }.sorted()

        withClue("the union is every user exactly once") { shared shouldContainExactly whole }
    }

    @Test
    fun `each shard sends only its own users`() {
        usersSentBy(index = 1).forEach { withClue("$it") { (it % 4).toInt() shouldBe 1 } }
    }

    @Test
    fun `an unsharded run is unchanged`() {
        usersSentBy(index = null).size shouldBe 20
    }

    @Test
    fun `a shard that is not one of them is refused where it is written`() {
        shouldThrow<IllegalArgumentException> { Shard(index = 4, of = 4, startingAt = whenever) }
        shouldThrow<IllegalArgumentException> { Shard(index = -1, of = 4, startingAt = whenever) }
        shouldThrow<IllegalArgumentException> { Shard(index = 0, of = 0, startingAt = whenever) }
    }

    @Test
    fun `every injector carries the whole plan, so none of them is unlike another`() {
        val at = shortly()
        val one = scenario("counting") { exec("touch") { } }
            .at(20.perSecond, over = 1.seconds).sharded(0, 4, at).plan()
        val other = scenario("counting") { exec("touch") { } }
            .at(20.perSecond, over = 1.seconds).sharded(3, 4, at).plan()

        withClue("a scaled-down profile per injector would force open the unlike-plan refusal") {
            one shouldBe other
        }
    }

    @Test
    fun `an injector told to start in the past writes nothing, and says how late it is`() {
        val counting = scenario("counting") { exec("touch") { } }

        val why = shouldThrow<IllegalArgumentException> {
            counting.at(20.perSecond, over = 1.seconds)
                .sharded(0, 4, Instant.now().minusSeconds(30))
                .run(Progress.silent)
        }

        withClue(why.message.orEmpty()) {
            why.message.orEmpty() shouldContain "injector 0 of 4"
            why.message.orEmpty() shouldContain "ago"
        }
    }

    @Test
    fun `two injectors given one instant start within a tenth of a second of each other`() {
        val counting = scenario("counting") { exec("touch") { } }
        val together = Instant.now().plusSeconds(2)

        val started = (0 until 2).toList().parallelStream().map { index ->
            counting.at(20.perSecond, over = 1.seconds).sharded(index, 2, together).run(Progress.silent).startedAt
        }.toList()

        val apart = kotlin.math.abs(started[0].toEpochMilli() - started[1].toEpochMilli())
        withClue("$started, ${apart}ms apart") { (apart < 100L) shouldBe true }
    }

    @Test
    fun `an injector holding for the instant says so, rather than looking hung`() {
        val watching = Holds()

        scenario("counting") { exec("touch") { } }
            .at(20.perSecond, over = 1.seconds)
            .sharded(1, 4, shortly())
            .run(watching)

        withClue("${watching.held}") { watching.held.single() shouldContain "injector 1 of 4" }
    }

    /** Named rather than a literal: an object with state is not a lambda. */
    private class Holds : Progress by Progress.silent {
        val held = mutableListOf<String>()

        override fun aligning(shard: Shard, until: Duration) {
            held += "injector ${shard.index} of ${shard.of} for $until"
        }
    }

    private companion object {
        val user = io.github.matthewjones372.proofload.sessionKey<Long>("user")
    }
}
