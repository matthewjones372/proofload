package io.github.matthewjones372.kestrel.examples

import io.github.matthewjones372.kestrel.engine.Kestrel
import io.github.matthewjones372.kestrel.engine.calibrate
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.time.Duration.Companion.seconds

/**
 * What a machine can resolve, asked of a real machine.
 *
 * Nothing here asserts a size. The floor is a measurement, and a test that
 * expected a particular one would be a threshold somebody picked on their own
 * hardware — which is the mistake the whole spec exists to stop. What is
 * asserted is that the measurement is taken, that it is a fraction of repeats
 * that really happened, and that asking twice does not measure twice.
 */
@Tag("timing")
class MachineFloorTest {

    @Test
    fun `calibrating twice measures the same machine twice, and says so both times`() {
        val first = calibrate(within = BUDGET)
        val second = calibrate(within = BUDGET)

        withClue("two readings of one machine: ${first.resolution} then ${second.resolution}") {
            listOf(first, second).forEach { floor ->
                (floor.resolution >= 0.0) shouldBe true
                floor.resolution.isFinite() shouldBe true
                (floor.hiccups.count > 0L) shouldBe true
            }
        }
    }

    /**
     * The stability the spec asks for is a decision rather than a hope: a floor
     * is a property of the machine, so it is measured once per JVM and kept. A
     * floor re-measured between two runs would be measuring the drift it exists
     * to bound.
     */
    @Test
    @Timeout(TIMEOUT_SECONDS)
    fun `the floor a test consults is measured once and is the same every time it is asked`() {
        val kestrel = Kestrel()

        val first = kestrel.calibrate()
        val again = kestrel.calibrate()
        val fromAnotherTest = Kestrel().calibrate()

        again shouldBe first
        withClue("one per test method, one floor: a second Kestrel must not re-measure the machine") {
            fromAnotherTest shouldBe first
        }
    }

    private companion object {
        /** Short enough to run twice inside one method; the shipped default is thirty seconds. */
        val BUDGET = 8.seconds

        /** The default calibration is bounded at thirty seconds, and this method pays for one. */
        const val TIMEOUT_SECONDS = 120L
    }
}
