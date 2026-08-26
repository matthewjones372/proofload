package io.github.matthewjones372.kestrel

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ArrivalsTest {

    @Test
    fun `an even profile arrives on the interval, so nothing varies`() {
        val arrivals = hold(200.perSecond, over = 10.seconds).recorded()

        arrivals.count shouldBe 2000L
        arrivals.mean shouldBe 5.milliseconds
        withClue("an even profile had a coefficient of variation of ${arrivals.cov}") {
            (arrivals.cov < 0.001) shouldBe true
        }
    }

    @Test
    fun `a randomised profile arrives the way a Poisson process arrives`() {
        val arrivals = hold(200.perSecond, over = 30.seconds).randomized(seed = 20260826).recorded()

        withClue("mean gap was ${arrivals.mean}") {
            (arrivals.mean > 4.milliseconds && arrivals.mean < 6.milliseconds) shouldBe true
        }
        withClue("a randomised profile had a coefficient of variation of ${arrivals.cov}") {
            (arrivals.cov > 0.8 && arrivals.cov < 1.2) shouldBe true
        }
    }

    @Test
    fun `nothing was measured until something departed`() {
        ArrivalRecorder().freeze() shouldBe Arrivals.none
    }

    @Test
    fun `one departure is no gap, so there is still nothing to average`() {
        val recorder = ArrivalRecorder().apply { record(Duration.ZERO) }

        recorder.freeze().count shouldBe 1L
        recorder.freeze().mean shouldBe Duration.ZERO
    }

    @Test
    fun `the seeds a shape draws from are what the report names it by`() {
        val even = hold(200.perSecond, over = 10.seconds)

        even.seeds shouldBe emptyList()
        even.randomized(seed = 7).seeds shouldBe listOf(7L)
        even.then(even.randomized(seed = 7)).seeds shouldBe listOf(7L)
        even.randomized(seed = 7).then(even.randomized(seed = 9)).seeds shouldBe listOf(7L, 9L)
    }

    private fun InjectionProfile.recorded(): Arrivals =
        ArrivalRecorder().apply { departures().forEach(::record) }.freeze()
}
