package io.github.matthewjones372.proofload

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

class ProbeTest {

    private val onTheBaselineMachine = Probe(50.microseconds)

    @Test
    fun `a machine that ran the same probe in twice the time is half as fast`() {
        Probe(100.microseconds).timesSlowerThan(onTheBaselineMachine) shouldBe 2.0
    }

    @Test
    fun `a machine that ran it in the same time is neither faster nor slower`() {
        Probe(50.microseconds).timesSlowerThan(onTheBaselineMachine) shouldBe 1.0
    }

    @Test
    fun `a probe a quarter slower is a different machine rather than the same one on a bad day`() {
        withClue("a fifth slower is inside what one machine does to itself") {
            Probe(60.microseconds).materiallySlowerThan(onTheBaselineMachine) shouldBe false
        }
        Probe(63.microseconds).materiallySlowerThan(onTheBaselineMachine) shouldBe true
    }

    @Test
    fun `a faster machine is not a slower one, however much faster it is`() {
        Probe(1.microseconds).materiallySlowerThan(onTheBaselineMachine) shouldBe false
    }

    @Test
    fun `a probe that took no time measured nothing to compare`() {
        shouldThrow<IllegalArgumentException> { Probe(Duration.ZERO) }
    }
}
