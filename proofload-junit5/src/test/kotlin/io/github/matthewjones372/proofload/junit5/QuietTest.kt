package io.github.matthewjones372.proofload.junit5

import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.engine.Proofload
import io.github.matthewjones372.proofload.failureRate
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.percent
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.sustainable
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
    fun `a run under a load test prints nothing`(proofload: Proofload) {
        val printed = printedBy { proofload.run(browsing.at(4.perSecond, over = 1.seconds)) }

        withClue("a load test that prints a line per five seconds is noise in a JUnit report") {
            printed.shouldBeEmpty()
        }
    }

    @LoadTest
    fun `a capacity search under a load test prints nothing either`(proofload: Proofload) {
        val search = browsing.sustainable(
            upTo = 10.perSecond,
            holding = 20.milliseconds,
            expecting = listOf(failureRate under 1.percent),
        )

        val printed = printedBy { proofload.run(search) }

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
