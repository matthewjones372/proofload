package io.github.matthewjones372.kestrel

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class StagesTest {

    @Test
    fun `a stage departs where the one before it finished, not where it started`() {
        val shape = hold(4.perSecond, over = 1.seconds).then(hold(2.perSecond, over = 1.seconds))

        shape.departures().toList() shouldBe listOf(
            0.milliseconds, 250.milliseconds, 500.milliseconds, 750.milliseconds,
            1000.milliseconds, 1500.milliseconds,
        )
    }

    @Test
    fun `a shape sends what its stages send between them`() {
        val shape = hold(50.perSecond, over = 1.minutes).then(hold(10.perSecond, over = 1.minutes))

        shape.userCount() shouldBe 3600L
    }

    @Test
    fun `a shape lasts as long as its stages together`() {
        hold(1.perSecond, over = 30.seconds).then(hold(1.perSecond, over = 90.seconds)).over shouldBe 2.minutes
    }

    @Test
    fun `chaining flattens, so two ways of writing one shape are one shape`() {
        val left = hold(1.perSecond, over = 1.seconds)
        val middle = hold(2.perSecond, over = 1.seconds)
        val right = hold(3.perSecond, over = 1.seconds)

        left.then(middle).then(right) shouldBe left.then(middle.then(right))
    }

    @Test
    fun `a ramp picks up from the rate the stage before it was running at`() {
        val shape = hold(20.perSecond, over = 1.seconds).thenRampTo(0.perSecond, over = 1.seconds)

        val ramp = (shape as InjectionProfile.Stages).stages.last()

        ramp shouldBe rampRate(from = 20.perSecond, to = 0.perSecond, over = 1.seconds)
    }

    @Test
    fun `a soak is a value that answers before anything is sent`() {
        val soak = rampRate(from = 0.perSecond, to = 200.perSecond, over = 1.minutes)
            .then(hold(200.perSecond, over = 10.minutes))
            .thenRampTo(0.perSecond, over = 1.minutes)

        soak.over shouldBe 12.minutes
        soak.userCount() shouldBe 6000L + 120_000L + 6000L
        withClue("departures must not go backwards") {
            soak.departures().zipWithNext().all { (earlier, later) -> later >= earlier } shouldBe true
        }
    }

    @Test
    fun `a stage that lasts no time is kept, because somebody meant it`() {
        val shape = hold(1.perSecond, over = 1.seconds).then(hold(9.perSecond, over = 0.seconds))

        (shape as InjectionProfile.Stages).stages.size shouldBe 2
        shape.userCount() shouldBe 1L
    }
}
