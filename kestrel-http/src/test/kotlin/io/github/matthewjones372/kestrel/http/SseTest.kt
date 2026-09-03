package io.github.matthewjones372.kestrel.http

import io.github.matthewjones372.kestrel.Progress
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.ScenarioBuilder
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.engine.run
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.step
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val opened = step("opened")

private val first = step("first")

private val each = step("each")

private val done = step("done")

/**
 * A feed that opens instantly and then goes silent reads the same as one
 * delivering sixty events a second, if all that is measured is the headers.
 */
class SseTest {

    private val events = 5

    private fun watched(
        server: TestServer,
        build: ScenarioBuilder.(SseTarget) -> Unit,
    ): RunResult = scenario("watching") { build(sse.baseUrl(server.baseUrl).at("/stream")) }
        .at(4.perSecond, over = 250.milliseconds)
        .run(Progress.silent)

    @Test
    fun `a feed opens, is read to the end and closes`() {
        streaming(events = events) { server ->
            val result = watched(server) { feed ->
                open(opened, feed)
                firstEvent(first, within = 5.seconds)
                cadence(each, count = events - 1, within = 5.seconds)
                stopReading(done)
            }

            result[opened].ok.count shouldBe 1L
            withClue("the round trip to the first event is one sample") { result[first].count shouldBe 1L }
            withClue("and every event after it is a gap") { result[each].count shouldBe (events - 1).toLong() }
            result[done].ok.count shouldBe 1L
        }
    }

    @Test
    fun `a gap is measured from the event before it, not from the request`() {
        streaming(events = events) { server ->
            val result = watched(server) { feed ->
                open(opened, feed)
                firstEvent(first, within = 5.seconds)
                cadence(each, count = events - 1, within = 5.seconds)
            }

            withClue("the feed waits before its first event and not before the rest") {
                result[each].serviceTime.max shouldBeLessThan result[first].serviceTime.p50
            }
            withClue("and the open ends when the target agreed to stream, not when it first spoke") {
                result[opened].serviceTime.max shouldBeLessThan result[first].serviceTime.p50
            }
        }
    }

    @Test
    fun `a heartbeat is counted and is not an event`() {
        // Three comments arrive at once, before any event. A comment counted as
        // an event would satisfy the wait below and report a feed that had said
        // nothing as one delivering.
        streaming(events = 1, comments = 3) { server ->
            val result = watched(server) { feed ->
                open(opened, feed)
                firstEvent(first, within = 5.seconds)
                cadence(each, count = 1, within = 500.milliseconds)
            }

            withClue("the one event satisfied the first wait, so nothing is left for the second") {
                result[first].ok.count shouldBe 1L
                result[each].failedWith(StreamEnded) shouldBe 1L
            }
        }
    }

    @Test
    fun `a cadence with no first event before it is refused rather than reporting a round trip`() {
        streaming(events = events) { server ->
            val result = watched(server) { feed ->
                open(opened, feed)
                cadence(each, count = 1, within = 5.seconds)
            }

            result[each].failedWith(NoFirstEvent) shouldBe 1L
        }
    }

    @Test
    fun `a feed that stops part-way reports what arrived and the failure`() {
        streaming(events = 2) { server ->
            val result = watched(server) { feed ->
                open(opened, feed)
                firstEvent(first, within = 5.seconds)
                cadence(each, count = 5, within = 2.seconds)
            }

            withClue("one gap arrived before the far end let go") {
                result[each].ok.count shouldBe 1L
                result[each].failedWith(StreamEnded) shouldBe 1L
            }
        }
    }

    @Test
    fun `an open against nothing listening is a failure rather than a throw`() {
        val result = scenario("watching") {
            open(opened, sse.baseUrl(closedPortUrl()).at("/stream"))
        }.at(4.perSecond, over = 250.milliseconds).run(Progress.silent)

        result[opened].failed.count shouldBe 1L
    }

    @Test
    fun `reading with nothing open fails the step rather than throwing`() {
        val result = scenario("watching") {
            firstEvent(first, within = 1.seconds)
        }.at(4.perSecond, over = 250.milliseconds).run(Progress.silent)

        result[first].failedWith(NotStreaming) shouldBe 1L
    }
}
