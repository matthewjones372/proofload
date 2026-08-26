package io.github.matthewjones372.kestrel.report

import io.github.matthewjones372.kestrel.Tail
import io.github.matthewjones372.kestrel.Timing
import io.github.matthewjones372.kestrel.interval
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * The p99.9 on the page: the percentile that separates two collectors matching
 * to p99, and the first one a short run has to refuse.
 */
class TailTest {

    private val timing = Fixtures.longEnoughForATail["pay"].responseTime
    private val page = Fixtures.longEnoughForATail.toHtmlReport()

    @Test
    fun `a run long enough for a tail prints it, with the interval it rests on`() {
        val tail = timing.p999.shouldBeInstanceOf<Tail.Measured>()
        val interval = requireNotNull(timing.interval(99.9))

        page shouldContain tail.duration.forReport()
        page shouldContain "${interval.low.forReport()}–${interval.high.forReport()}"
    }

    @Test
    fun `the tail is where the slow requests are, rather than the p99 printed twice`() {
        val tail = timing.p999.shouldBeInstanceOf<Tail.Measured>()

        withClue("p99 ${timing.p99}, p99.9 ${tail.duration}") {
            (tail.duration > timing.p99) shouldBe true
        }
    }

    @Test
    fun `a step too thin for a tail says so where the number would be, and says how thin`() {
        val thin = Fixtures.fellBehind.toHtmlReport()

        thin shouldContain "only 5 samples"
        thin shouldContain "Not measured"
    }

    @Test
    fun `the page names the same threshold the value refuses below`() {
        Fixtures.fellBehind.toHtmlReport() shouldContain Timing.SAMPLES_FOR_P999.grouped()
    }
}
