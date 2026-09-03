package io.github.matthewjones372.kestrel

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Composition over the interface that exists, so none of this waits on a clock. */
class ComposedProgressTest {

    private class Heard : Progress {
        val ticks = mutableListOf<Duration>()
        val started = mutableListOf<Plan>()
        val queues = mutableListOf<Duration>()
        val alignments = mutableListOf<Shard>()

        override fun tick(elapsed: Duration, snapshot: Snapshot) {
            ticks += elapsed
        }

        override fun starting(plan: Plan) {
            started += plan
        }

        override fun waited(queued: Duration) {
            queues += queued
        }

        override fun aligning(shard: Shard, until: Duration) {
            alignments += shard
        }
    }

    private fun snapshot(ended: Boolean = false): Snapshot =
        Snapshot(departed = 0L, inFlight = 0L, behind = Duration.ZERO, ended = ended)

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
    fun `both reporters see every tick`() {
        val terminal = Heard()
        val collector = Heard()

        val both = terminal and collector
        (1..3).forEach { second -> both.tick(second.seconds, snapshot()) }

        terminal.ticks shouldContainExactly listOf(1.seconds, 2.seconds, 3.seconds)
        collector.ticks shouldContainExactly terminal.ticks
    }

    @Test
    fun `both reporters hear everything else a run announces`() {
        val terminal = Heard()
        val collector = Heard()
        val plan = Plan(scenario = "checkout", steps = listOf("browse"), profile = null)
        val shard = Shard(index = 1, of = 4, startingAt = Instant.EPOCH)

        val both = terminal and collector
        both.starting(plan)
        both.waited(2.seconds)
        both.aligning(shard, 3.seconds)

        listOf(terminal, collector).forEach { heard ->
            withClue("every callback reaches both sides, not only tick") {
                heard.started shouldContainExactly listOf(plan)
                heard.queues shouldContainExactly listOf(2.seconds)
                heard.alignments shouldContainExactly listOf(shard)
            }
        }
    }

    @Test
    fun `two silent reporters print nothing`() {
        val printed = printedBy {
            val both = Progress.silent and Progress.silent
            both.tick(1.seconds, snapshot())
            both.tick(2.seconds, snapshot(ended = true))
        }

        printed.shouldBeEmpty()
    }

    @Test
    fun `a throttled reporter sees at most one tick an interval`() {
        val collector = Heard()

        val throttled = collector.throttled(every = 5.seconds)
        (1..10).forEach { second -> throttled.tick(second.seconds, snapshot()) }

        collector.ticks shouldContainExactly listOf(5.seconds, 10.seconds)
    }

    @Test
    fun `a throttled reporter always sees the last tick of the run`() {
        val collector = Heard()

        val throttled = collector.throttled(every = 5.seconds)
        throttled.tick(1.seconds, snapshot())
        throttled.tick(2.seconds, snapshot(ended = true))

        withClue("a run shorter than one interval still says what it did") {
            collector.ticks shouldContainExactly listOf(2.seconds)
        }
    }

    @Test
    fun `throttling holds the ticks and nothing else`() {
        val collector = Heard()

        val throttled = collector.throttled(every = 1.minutes)
        throttled.waited(4.seconds)
        throttled.tick(1.seconds, snapshot())

        collector.queues shouldContainExactly listOf(4.seconds)
        collector.ticks.shouldBeEmpty()
    }
}
