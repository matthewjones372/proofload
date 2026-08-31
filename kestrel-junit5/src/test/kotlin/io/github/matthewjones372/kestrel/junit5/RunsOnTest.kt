package io.github.matthewjones372.kestrel.junit5

import io.github.matthewjones372.kestrel.Engine
import io.github.matthewjones372.kestrel.RunRecorder
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.Simulation
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.engine.Kestrel
import io.github.matthewjones372.kestrel.failureRate
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.percent
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.sustainable
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A load test in a class that names an engine is sent by that engine, and the
 * runner it is handed says no more than the default one does.
 */
class RunsOnTest : RunsOn {

    private val recording = Recording()

    override val engine: Engine = recording

    private val browsing = scenario("browse") { exec("home") { } }

    @LoadTest
    fun `a load test is sent by the engine its class names`(kestrel: Kestrel) {
        val simulation = browsing.at(4.perSecond, over = 1.seconds)

        val result = kestrel.run(simulation)

        recording.ran shouldContainExactly listOf(simulation)
        withClue("what the named engine answered with is what the test is given") {
            result shouldBe recording.answer
        }
    }

    @LoadTest
    fun `every rung of a search under a load test is sent by that same engine`(kestrel: Kestrel) {
        val search = browsing.sustainable(
            upTo = 10.perSecond,
            holding = 20.milliseconds,
            expecting = listOf(failureRate under 1.percent),
        )

        val capacity = kestrel.run(search)

        recording.ran shouldContainExactly capacity.curve.map { search.at(it.rate) }
    }

    @LoadTest
    fun `a search on a named engine prints nothing either`(kestrel: Kestrel) {
        val search = browsing.sustainable(
            upTo = 10.perSecond,
            holding = 20.milliseconds,
            expecting = listOf(failureRate under 1.percent),
        )

        val printed = printedBy { kestrel.run(search) }

        withClue("naming an engine must not put progress lines back into a JUnit report") {
            printed.shouldBeEmpty()
        }
    }
}

/** Records what it was asked for and sends nothing, so a load test through it offers no load. */
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
