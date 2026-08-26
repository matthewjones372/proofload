package io.github.matthewjones372.kestrel.engine

import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.fedBy
import io.github.matthewjones372.kestrel.feed
import io.github.matthewjones372.kestrel.feedFrom
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.sessionKey
import io.github.matthewjones372.kestrel.step
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.seconds

private val email = sessionKey<String>("email")
private val region = sessionKey<String>("region")
private val send = step("send")

class FeedingTest {

    @Test
    fun `each user sends the data the feeder gave it, so a cache cannot answer twice`() {
        val seen = ConcurrentLinkedQueue<String>()
        val sending = scenario("sending") {
            exec(send) { get(email)?.let(seen::add) }
        }

        sending.at(4.perSecond, over = 1.seconds)
            .fedBy(feed(email) { user -> "user$user@example.com" })
            .run()

        seen.toList() shouldContainExactlyInAnyOrder listOf(
            "user0@example.com", "user1@example.com", "user2@example.com", "user3@example.com",
        )
    }

    @Test
    fun `a list feeder is spread across the users rather than given to the first`() {
        val seen = ConcurrentLinkedQueue<String>()
        val sending = scenario("sending") { exec(send) { get(region)?.let(seen::add) } }

        sending.at(4.perSecond, over = 1.seconds)
            .fedBy(feedFrom(region, listOf("eu-west", "us-east")))
            .run()

        seen.toList().toSet() shouldBe setOf("eu-west", "us-east")
    }

    @Test
    fun `a simulation nobody fed still starts every user empty`() {
        val seen = ConcurrentLinkedQueue<Boolean>()
        val sending = scenario("sending") { exec(send) { seen.add(get(email) == null) } }

        sending.at(2.perSecond, over = 1.seconds).run()

        seen.toList() shouldBe listOf(true, true)
    }
}
