package io.github.matthewjones372.proofload.kotest

import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.failureRate
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.percent
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.sustainable
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val browsing = scenario("browse") { exec("home") { } }

/**
 * A Kotest report is somebody else's output, so the runner a spec is handed
 * writes nothing to stdout — for a run and for a search alike, which report
 * through two different reporters.
 */
class QuietSpecTest : StringSpec({

    "a run in a spec prints nothing" {
        val proofload = proofload()

        val printed = printedBy { proofload.run(browsing.at(4.perSecond, over = 1.seconds)) }

        withClue("a spec that prints a line per five seconds is noise in a Kotest report") {
            printed.shouldBeEmpty()
        }
    }

    "a capacity search in a spec prints nothing either" {
        val proofload = proofload()
        val search = browsing.sustainable(
            upTo = 10.perSecond,
            holding = 20.milliseconds,
            expecting = listOf(failureRate under 1.percent),
        )

        val printed = printedBy { proofload.run(search) }

        printed.shouldBeEmpty()
    }
})

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
