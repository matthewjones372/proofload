package io.github.matthewjones372.kestrel.kotest

import io.github.matthewjones372.kestrel.Engine
import io.github.matthewjones372.kestrel.RunRecorder
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.Simulation
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.failureRate
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.percent
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.sustainable
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val browsing = scenario("browse") { exec("home") { } }

/**
 * A spec names the engine its runs go to, and the runner it gets back says no
 * more than the one it gets for free.
 */
class ChosenEngineSpecTest : StringSpec({

    "a spec is sent by the engine it names" {
        val recording = Recording()
        val simulation = browsing.at(4.perSecond, over = 1.seconds)

        val result = kestrel(recording).run(simulation)

        recording.ran shouldContainExactly listOf(simulation)
        withClue("what the named engine answered with is what the spec is given") {
            result shouldBe recording.answer
        }
    }

    "every rung of a search in a spec is sent by that same engine" {
        val recording = Recording()
        val search = browsing.sustainable(
            upTo = 10.perSecond,
            holding = 20.milliseconds,
            expecting = listOf(failureRate under 1.percent),
        )

        val capacity = kestrel(recording).run(search)

        recording.ran shouldContainExactly capacity.curve.map { search.at(it.rate) }
    }

    "a search on a named engine prints nothing either" {
        val search = browsing.sustainable(
            upTo = 10.perSecond,
            holding = 20.milliseconds,
            expecting = listOf(failureRate under 1.percent),
        )

        val printed = printedBy { kestrel(Recording()).run(search) }

        withClue("naming an engine must not put progress lines back into a Kotest report") {
            printed.shouldBeEmpty()
        }
    }
})

/** Records what it was asked for and sends nothing, so a spec through it offers no load. */
private class Recording : Engine {

    val answer: RunResult = RunRecorder(Instant.now()).freeze()

    private val sent = mutableListOf<Simulation>()

    val ran: List<Simulation> get() = sent

    override fun run(simulation: Simulation): RunResult = answer.also { sent += simulation }
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
