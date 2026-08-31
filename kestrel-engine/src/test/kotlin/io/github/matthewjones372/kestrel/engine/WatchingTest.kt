package io.github.matthewjones372.kestrel.engine

import io.github.matthewjones372.kestrel.Progress
import io.github.matthewjones372.kestrel.Snapshot
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.scenario
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

class WatchingTest {

    private val browsing = scenario("watched") { exec("browse") { } }

    private fun printedBy(block: () -> Unit): String {
        val captured = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(captured, true, Charsets.UTF_8))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return captured.toString(Charsets.UTF_8)
    }

    @Test
    fun `a run ticks while it is going, and its last tick counts every departure`() {
        val ticks = CopyOnWriteArrayList<Snapshot>()

        val result = browsing.at(5.perSecond, over = 2.seconds)
            .run(Progress { _, snapshot -> ticks += snapshot })

        withClue("a run outliving the engine's sampling interval is watched, not only summarised") {
            ticks.size shouldBeGreaterThan 1
        }
        val last = ticks.last()
        withClue("the scheduler's own count, against the run it froze") {
            last.departed shouldBe result.arrivals.count
        }
        last.inFlight shouldBe 0L
        last.ended shouldBe true
    }

    @Test
    fun `a run says what it did, and says nothing when it is told to be silent`() {
        val simulation = browsing.at(2.perSecond, over = 1.seconds)

        printedBy { simulation.run() } shouldContain "kestrel: "
        printedBy { simulation.run(Progress.silent) } shouldNotContain "kestrel: "
    }
}
