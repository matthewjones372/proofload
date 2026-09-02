package io.github.matthewjones372.kestrel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A fixed population has no schedule, and almost every figure a run reports
 * about its own schedule assumes one. Each of them either has a value a closed
 * run can honestly produce, is absent, or is refused where it is written.
 */
class ClosedModelTest {

    private val browse = step("browse")

    private val looping = scenario("looping") { exec(browse) { } }

    @Test
    fun `a population is what it names, not a count of departures`() {
        val fifty = users(50, over = 10.minutes)

        fifty.userCount() shouldBe 50L
        fifty.over shouldBe 10.minutes
    }

    @Test
    fun `a population under one is refused where it is written`() {
        val why = shouldThrow<IllegalArgumentException> { users(0, over = 1.minutes) }.message.orEmpty()

        withClue(why) { why shouldContain "at least one user" }
    }

    @Test
    fun `there is no interval to be late against, so there is none`() {
        val plan = looping.at(users(50, over = 1.minutes)).plan()

        withClue("zero rather than a window divided by a population, which would be a number about nothing") {
            plan.plannedInterval shouldBe Duration.ZERO
        }
    }

    @Test
    fun `a closed profile has no departure offsets, and says so rather than producing none`() {
        val why = shouldThrow<IllegalStateException> {
            users(50, over = 1.minutes).departures().take(1).toList()
        }.message.orEmpty()

        withClue(why) { why shouldContain "no departure offsets" }
    }

    @Test
    fun `a schedule goal cannot be judged against a schedule that does not exist`() {
        val why = shouldThrow<IllegalArgumentException> {
            looping.at(users(50, over = 1.minutes)).expecting(keptSchedule)
        }.message.orEmpty()

        withClue(why) { why shouldContain "keeps no schedule" }
    }

    @Test
    fun `a closed profile is refused inside the shapes that space departures`() {
        val population = users(50, over = 1.minutes)

        listOf<() -> Any>(
            { population.randomized(38) },
            { population then hold(10.perSecond, over = 1.seconds) },
            { hold(10.perSecond, over = 1.seconds) then population },
        ).forEach { built ->
            val why = shouldThrow<IllegalArgumentException> { built() }.message.orEmpty()
            withClue(why) { why shouldContain "closed" }
        }
    }

    @Test
    fun `a closed plan and an open one are not one population`() {
        val closed = looping.at(users(50, over = 1.minutes)).plan()
        val open = looping.at(50.perSecond, over = 1.minutes).plan()

        withClue("nothing may pool a run that had a schedule with one that never did") {
            closed.unlike(open).isNotEmpty() shouldBe true
        }
    }

    @Test
    fun `an open plan is unchanged by any of this`() {
        val open = looping.at(50.perSecond, over = 1.minutes).plan()

        open.unlike(open).isEmpty() shouldBe true
        withClue("fifty a second for a minute is three thousand users, one every twenty milliseconds") {
            open.plannedInterval shouldBe 20.milliseconds
        }
    }

    @Test
    fun `a run with no promised departures reports no lateness, rather than none late`() {
        val result = RunResult(
            startedAt = java.time.Instant.parse("2026-09-02T09:00:00Z"),
            steps = emptyMap(),
            behind = Timing.none,
            plan = looping.at(users(50, over = 1.minutes)).plan(),
        )

        withClue("zero would read as perfect punctuality rather than a question that does not apply") {
            result.lostGround() shouldBe false
            result.heldScheduleFor shouldBe null
            result.offered shouldBe null
        }
    }

    @Test
    fun `the pause a user takes is unaffected, because it was never about a schedule`() {
        val thinking = scenario("thinking") {
            exec(browse) { }
            pause(20.milliseconds)
        }

        thinking.at(users(5, over = 1.seconds)).plan().steps shouldBe listOf("browse")
    }
}
