package io.github.matthewjones372.kestrel.engine

import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.scenario
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class HiccupsTest {

    private val tick = 1.milliseconds

    @Test
    fun `a tick that arrived when it was due stalled for nothing`() {
        val hiccups = Hiccups(startedAt = 0L, interval = tick)

        hiccups.observe(firedAt = 1_000_000L)
        hiccups.observe(firedAt = 2_000_000L)

        hiccups.frozen().count shouldBe 2L
        hiccups.frozen().max shouldBe Duration.ZERO
    }

    @Test
    fun `a tick the scheduler ran early is not a negative stall`() {
        val hiccups = Hiccups(startedAt = 0L, interval = tick)

        hiccups.observe(firedAt = 900_000L)

        hiccups.frozen().max shouldBe Duration.ZERO
    }

    /**
     * Each tick is due at its own multiple of the interval, so the catch-up
     * burst after a stall reports the stall shrinking rather than one sample
     * and a gap where the others should have been.
     */
    @Test
    fun `a stall is as long as the tick it held up, not as long as the gap to the next one`() {
        val hiccups = Hiccups(startedAt = 0L, interval = tick)

        hiccups.observe(firedAt = 1_000_000L)
        hiccups.observe(firedAt = 52_000_000L)
        hiccups.observe(firedAt = 52_100_000L)

        val frozen = hiccups.frozen()
        frozen.count shouldBe 3L
        withClue("the tick due at 2ms fired at 52ms: ${frozen.max}") {
            frozen.max shouldBeGreaterThanOrEqualTo 50.milliseconds
        }
    }

    @Test
    fun `a pause on the injector's JVM lands in the hiccup distribution`() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val watch = watchForHiccups(tick, executor)

        // The pause, on the one thread that measures: everything it holds up
        // is overdue and runs before the barrier below can.
        executor.submit { Thread.sleep(pause.inWholeMilliseconds) }.get()
        executor.submit { }.get()

        val hiccups = watch.stop()
        withClue("a $pause pause with nothing else to blame it on: ${hiccups.max}") {
            hiccups.max shouldBeGreaterThanOrEqualTo pause * TOLERANCE
        }
    }

    @Test
    fun `a run watches its own JVM while it sends`() {
        val result = scenario("checkout") { exec("browse") { } }
            .at(50.perSecond, over = 1.seconds)
            .run()

        withClue("a second of a run at a tick a millisecond: ${result.hiccups.count} samples") {
            (result.hiccups.count > 100L) shouldBe true
        }
    }

    private companion object {
        val pause = 100.milliseconds

        /** Room for the tick that was due a fraction before the pause began. */
        const val TOLERANCE = 0.9
    }
}
