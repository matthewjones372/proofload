package io.github.matthewjones372.kestrel.junit5

import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.engine.Kestrel
import io.github.matthewjones372.kestrel.failureRate
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.percent
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.sustainable
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A JUnit report is somebody else's output, so the runner this extension hands
 * a test writes nothing to stdout — neither the per-run lines the engine emits
 * nor the per-rung lines a search emits, which come from two different
 * reporters and so are two different ways to get this wrong.
 */
class QuietTest {

    private val browsing = scenario("browse") { exec("home") { } }

    @LoadTest
    fun `a run under a load test prints nothing`(kestrel: Kestrel) {
        val printed = printedBy { kestrel.run(browsing.at(4.perSecond, over = 1.seconds)) }

        withClue("a load test that prints a line per five seconds is noise in a JUnit report") {
            printed.shouldBeEmpty()
        }
    }

    @LoadTest
    fun `a capacity search under a load test prints nothing either`(kestrel: Kestrel) {
        val search = browsing.sustainable(
            upTo = 10.perSecond,
            holding = 20.milliseconds,
            expecting = listOf(failureRate under 1.percent),
        )

        val printed = printedBy { kestrel.run(search) }

        withClue("a search reports through the runner's own reporter rather than the engine's") {
            printed.shouldBeEmpty()
        }
    }
}

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
