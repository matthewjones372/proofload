package io.github.matthewjones372.proofload

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What `lines()` prints is a function of the ticks it was handed, so none of
 * this waits on a clock.
 */
class ProgressTest {

    private fun snapshot(
        departed: Long = 0L,
        inFlight: Long = 0L,
        behind: Duration = Duration.ZERO,
        ended: Boolean = false,
        scheduled: Duration = Duration.ZERO,
    ): Snapshot = Snapshot(
        departed = departed,
        inFlight = inFlight,
        behind = behind,
        ended = ended,
        scheduled = scheduled,
    )

    private fun printedBy(block: () -> Unit): List<String> {
        val captured = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(captured, true, Charsets.UTF_8))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return captured.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
    }

    @Test
    fun `a line no more often than the interval, however often the run ticks`() {
        val progress = Progress.lines(every = 5.seconds)

        val printed = printedBy {
            (1..10).forEach { second -> progress.tick(second.seconds, snapshot(departed = second * 10L)) }
        }

        printed shouldHaveSize 2
        printed.first() shouldContain "00:05"
        printed.last() shouldContain "00:10"
    }

    @Test
    fun `the tick that ends the run prints however short the run was`() {
        val printed = printedBy {
            Progress.lines(every = 5.seconds).tick(1_300.milliseconds, snapshot(departed = 7L, ended = true))
        }

        withClue("a run shorter than one interval would otherwise say nothing at all") {
            printed shouldHaveSize 1
        }
        printed.single() shouldContain "departed 7"
    }

    @Test
    fun `a line says how long the run has been going and what the scheduler sent`() {
        val printed = printedBy {
            Progress.lines().tick(
                90.seconds,
                snapshot(departed = 44_231L, inFlight = 312L, behind = 2.milliseconds, ended = true),
            )
        }

        printed.single() shouldBe "proofload: 01:30  departed 44,231  in flight 312  behind 2ms"
    }

    @Test
    fun `a run with a window on it counts down to the end of the schedule`() {
        val printed = printedBy {
            Progress.lines().tick(
                90.seconds,
                snapshot(departed = 4_500L, ended = true, scheduled = 10.minutes),
            )
        }

        printed.single() shouldContain "08:30 left"
    }

    @Test
    fun `a part second still to run reads as a second rather than as none`() {
        val printed = printedBy {
            Progress.lines().tick(2200.milliseconds, snapshot(departed = 44L, ended = true, scheduled = 3.seconds))
        }

        withClue("800ms of schedule is left, and 00:00 would say the run had stopped asking") {
            printed.single() shouldContain "00:01 left"
        }
    }

    @Test
    fun `past the window the line says draining rather than a countdown nobody can make`() {
        val printed = printedBy {
            Progress.lines().tick(
                10.minutes,
                snapshot(departed = 30_000L, inFlight = 41L, ended = true, scheduled = 10.minutes),
            )
        }

        withClue("what is left after the schedule is the target's, and this end of the wire does not know it") {
            printed.single() shouldContain "draining"
        }
    }

    @Test
    fun `a run says its shape before it departs, read off the plan rather than measured`() {
        val plan = Plan(
            scenario = "checkout",
            steps = listOf("/products", "/orders"),
            profile = constantRate(50.perSecond, over = 10.minutes),
        )

        val printed = printedBy { Progress.lines().starting(plan) }

        printed.single() shouldBe "proofload: checkout — 30,000 users over 10m, 2 steps each"
    }

    @Test
    fun `a result built from samples has no profile, so there is no shape to announce`() {
        printedBy { Progress.lines().starting(Plan.none) }.shouldBeEmpty()
    }

    @Test
    fun `silent says nothing about the shape either`() {
        val plan = Plan("checkout", listOf("/products"), constantRate(50.perSecond, over = 1.minutes))

        printedBy { Progress.silent.starting(plan) }.shouldBeEmpty()
    }

    @Test
    fun `silent prints nothing, whatever it is told`() {
        val printed = printedBy {
            Progress.silent.tick(5.seconds, snapshot(departed = 100L, ended = true))
        }

        printed.shouldBeEmpty()
    }
}
