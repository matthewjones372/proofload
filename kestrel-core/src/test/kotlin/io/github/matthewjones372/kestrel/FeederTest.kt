package io.github.matthewjones372.kestrel

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

private val email = sessionKey<String>("email")
private val region = sessionKey<String>("region")
private val attempt = sessionKey<Long>("attempt")

class FeederTest {

    @Test
    fun `a computed feeder gives each user its own value`() {
        val users = feed(email) { user -> "user$user@example.com" }

        users.forUser(0)[email] shouldBe "user0@example.com"
        users.forUser(41)[email] shouldBe "user41@example.com"
    }

    @Test
    fun `a list feeder hands out its values in order`() {
        val users = feedFrom(region, listOf("eu-west", "us-east"))

        users.forUser(0)[region] shouldBe "eu-west"
        users.forUser(1)[region] shouldBe "us-east"
    }

    @Test
    fun `a list wraps round rather than ending the run`() {
        val users = feedFrom(region, listOf("eu-west", "us-east"))

        users.forUser(2)[region] shouldBe "eu-west"
        users.forUser(1_000_001)[region] shouldBe "us-east"
    }

    @Test
    fun `two feeders combine into one that fills both keys`() {
        val users = feed(email) { "user$it@example.com" } + feedFrom(region, listOf("eu-west"))

        val session = users.forUser(0)
        session[email] shouldBe "user0@example.com"
        session[region] shouldBe "eu-west"
    }

    @Test
    fun `when two feeders fill one key the later one wins, as an override reads everywhere else`() {
        val users = feedFrom(region, listOf("first")) + feedFrom(region, listOf("second"))

        users.forUser(0)[region] shouldBe "second"
    }

    @Test
    fun `the same user gets the same data twice, so a failure can be looked at`() {
        val users = feed(attempt) { user -> user * 7L }

        users.forUser(93) shouldBe users.forUser(93)
    }

    @Test
    fun `a simulation says what it feeds before it runs anything`() {
        val users = feed(email) { "user$it@example.com" }
        val simulation = scenario("s") { exec("step") { } }.at(1.perSecond, over = 1.seconds).fedBy(users)

        simulation.feeder shouldBe users
        simulation.feeder.forUser(3)[email] shouldBe "user3@example.com"
    }

    @Test
    fun `a simulation nobody fed starts its users empty`() {
        val simulation = scenario("s") { exec("step") { } }.at(1.perSecond, over = 1.seconds)

        simulation.feeder.forUser(7) shouldBe Session.empty
    }

    @Test
    fun `an empty list feeds nothing rather than dividing by zero`() {
        feedFrom(region, emptyList()).forUser(3) shouldBe Session.empty
    }
}
