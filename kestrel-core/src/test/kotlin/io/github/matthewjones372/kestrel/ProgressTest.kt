package io.github.matthewjones372.kestrel

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
    ): Snapshot = Snapshot(departed = departed, inFlight = inFlight, behind = behind, ended = ended)

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

        printed.single() shouldBe "kestrel: 01:30  departed 44,231  in flight 312  behind 2ms"
    }

    @Test
    fun `silent prints nothing, whatever it is told`() {
        val printed = printedBy {
            Progress.silent.tick(5.seconds, snapshot(departed = 100L, ended = true))
        }

        printed.shouldBeEmpty()
    }
}
