package io.github.matthewjones372.kestrel.engine

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
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class KestrelTest {

    private val browsing = scenario("checkout") { exec("browse") { } }

    @Test
    fun `a simulation is sent by the engine it was handed rather than by this module's own`() {
        val recording = Recording()
        val simulation = browsing.at(1.perSecond, over = 1.seconds)

        val result = Kestrel(engine = recording).run(simulation)

        recording.ran shouldContainExactly listOf(simulation)
        withClue("what the engine answered with is what the caller is given") {
            result shouldBe recording.answer
        }
    }

    @Test
    fun `every rung of a search is sent by that same engine`() {
        val recording = Recording()
        val search = browsing.sustainable(
            upTo = 10.perSecond,
            holding = 10.milliseconds,
            expecting = listOf(failureRate under 1.percent),
        )

        val capacity = Kestrel(engine = recording).run(search)

        recording.ran shouldContainExactly capacity.curve.map { search.at(it.rate) }
    }

    /** The default is what the seam has to leave alone: naming no engine still runs on Loom. */
    @Test
    fun `a Kestrel nobody handed an engine runs its users on virtual threads`() {
        val virtual = AtomicBoolean(false)

        Kestrel().run(
            scenario("checkout") { exec("browse") { virtual.set(Thread.currentThread().isVirtual) } }
                .at(1.perSecond, over = 1.seconds),
        )

        virtual.get() shouldBe true
    }
}

/** Records what it was asked for and sends nothing, so a run through it offers no load. */
private class Recording : Engine {

    val answer: RunResult = RunRecorder(Instant.now()).freeze()

    private val sent = mutableListOf<Simulation>()

    val ran: List<Simulation> get() = sent

    override fun run(simulation: Simulation): RunResult = answer.also { sent += simulation }
}
