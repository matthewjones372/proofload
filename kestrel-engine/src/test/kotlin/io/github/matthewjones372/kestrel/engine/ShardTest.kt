package io.github.matthewjones372.kestrel.engine

import io.github.matthewjones372.kestrel.Progress
import io.github.matthewjones372.kestrel.Shard
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.fedBy
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.plan
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.sharded
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.seconds

/**
 * Four injectors have to offer exactly the departures one JVM would, at the
 * same offsets, each user sent once. Anything else is four experiments that
 * cannot honestly be pooled.
 */
class ShardTest {

    private val whenever = Instant.parse("2026-08-26T12:00:00Z")

    /** Which users a run recorded, by the value each wrote from its own feeder. */
    private fun usersSentBy(index: Int?, of: Int = 4): List<Long> {
        val sent = ConcurrentLinkedQueue<Long>()
        val counting = scenario("counting") { exec("touch") { sent += this[user] ?: -1L } }
        val run = counting.at(20.perSecond, over = 1.seconds)
            .fedBy(io.github.matthewjones372.kestrel.feed(user) { it })
            .let { if (index == null) it else it.sharded(index, of, whenever) }

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
        val one = scenario("counting") { exec("touch") { } }
            .at(20.perSecond, over = 1.seconds).sharded(0, 4, whenever).plan()
        val other = scenario("counting") { exec("touch") { } }
            .at(20.perSecond, over = 1.seconds).sharded(3, 4, whenever).plan()

        withClue("a scaled-down profile per injector would force open the unlike-plan refusal") {
            one shouldBe other
        }
    }

    private companion object {
        val user = io.github.matthewjones372.kestrel.sessionKey<Long>("user")
    }
}
