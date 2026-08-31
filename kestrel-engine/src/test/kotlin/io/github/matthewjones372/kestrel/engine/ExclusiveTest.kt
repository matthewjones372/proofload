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
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ExclusiveTest {

    private val browsing = scenario("checkout") { exec("browse") { } }

    @Test
    fun `two threads asking for a run are never inside one at the same time`() {
        val overlapping = Overlapping()
        val kestrel = Kestrel(engine = overlapping.exclusive())
        val simulation = browsing.at(1.perSecond, over = 1.seconds)

        List(2) { Thread { kestrel.run(simulation) } }.onEach { it.start() }.forEach { it.join() }

        withClue("both threads reached the engine, and one of them should have waited") {
            overlapping.most shouldBe 1
        }
    }

    /** The deadlock this is a gate against hangs rather than fails, hence the timeout. */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS, threadMode = SEPARATE_THREAD)
    fun `a capacity search does not queue behind the machine its own rungs are running on`() {
        val search = browsing.sustainable(
            upTo = 10.perSecond,
            holding = 10.milliseconds,
            expecting = listOf(failureRate under 1.percent),
        )

        val capacity = Kestrel(engine = Answering().exclusive()).run(search)

        capacity.curve.shouldNotBeEmpty()
    }
}

/**
 * Sends nothing and counts how many callers were inside [run] at once.
 *
 * Each caller waits for a second one to arrive before leaving, so an engine
 * that let both in is seen to have; where only one can be inside, the wait is
 * given up rather than required.
 */
private class Overlapping : Engine {

    private val inside = AtomicInteger()

    private val peak = AtomicInteger()

    private val both = CountDownLatch(2)

    private val answer = RunRecorder(Instant.now()).freeze()

    val most: Int get() = peak.get()

    override fun run(simulation: Simulation): RunResult {
        peak.accumulateAndGet(inside.incrementAndGet()) { seen, now -> maxOf(seen, now) }
        both.countDown()
        both.await(GRACE_MILLIS, TimeUnit.MILLISECONDS)
        inside.decrementAndGet()
        return answer
    }
}

/** Answers every rung with an empty result, so a search climbs to its ceiling and stops. */
private class Answering : Engine {

    private val answer = RunRecorder(Instant.now()).freeze()

    override fun run(simulation: Simulation): RunResult = answer
}

private const val GRACE_MILLIS = 200L
