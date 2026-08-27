package io.github.matthewjones372.kestrel.junit5

import io.github.matthewjones372.kestrel.Difference
import io.github.matthewjones372.kestrel.Machine
import io.github.matthewjones372.kestrel.Spread
import io.github.matthewjones372.kestrel.p99
import io.github.matthewjones372.kestrel.percent
import io.github.matthewjones372.kestrel.step
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.opentest4j.AssertionFailedError

private val pay = step("pay")

private val here = Machine(cores = 8, jdk = "21.0.2+13", os = "Linux", arch = "aarch64")

private fun difference(interval: Spread) = Difference(
    statistic = p99(pay),
    before = 100.0,
    now = 105.0,
    runs = 10,
    baselineRuns = 10,
    acceptable = 3.percent,
    machine = here,
    baselineMachine = here,
    interval = interval,
)

private val slower = difference(Spread(low = 1.04, high = 1.06))
private val faster = difference(Spread(low = 0.90, high = 0.95))
private val noisy = difference(Spread(low = 0.98, high = 1.09))

class DifferencesTest {

    @Test
    fun `a statistic that got better is not worse than what was declared acceptable`() {
        faster.assertNotWorseThan(3.percent)
    }

    @Test
    fun `a statistic past the threshold fails, and the message says what moved`() {
        val failure = assertThrows<AssertionFailedError> { slower.assertNotWorseThan(3.percent) }

        failure.message shouldContain "pay p99 is worse than the 3% declared acceptable"
        failure.message shouldContain "10 runs against 10"
    }

    @Test
    fun `a comparison that cannot tell passes, because a noisy Tuesday is not a regression`() {
        noisy.assertNotWorseThan(3.percent)
    }

    @Test
    fun `unless the team asked to be stopped by one`() {
        val failure = assertThrows<AssertionFailedError> {
            noisy.assertNotWorseThan(3.percent, orCannotTell = true)
        }

        failure.message shouldContain "cannot tell"
        failure.message shouldContain "What would change it"
    }
}
