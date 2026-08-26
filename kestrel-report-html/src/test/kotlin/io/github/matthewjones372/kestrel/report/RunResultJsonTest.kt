package io.github.matthewjones372.kestrel.report

import io.github.matthewjones372.kestrel.Histogram
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.StepStats
import io.github.matthewjones372.kestrel.timing
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

class RunResultJsonTest {

    @Test
    fun `a run result encodes to the JSON its golden holds`() {
        Fixtures.fellBehind.toJson() shouldBe Golden.text("run-result.json")
    }

    @Test
    fun `durations are the nanoseconds that were measured, not a rounded millisecond`() {
        val ten = Histogram().apply { record(10.milliseconds) }.timing()
        val one = RunResult(
            startedAt = Instant.EPOCH,
            steps = mapOf(
                "only" to StepStats(
                    name = "only",
                    count = 1L,
                    ok = 1L,
                    failures = emptyMap(),
                    serviceTime = ten,
                    responseTime = ten,
                ),
            ),
            behind = ten,
        )

        // The top of the bucket 10 ms fell in: what the histogram can say, and
        // not one digit more.
        one.toJson() shouldContain """"max": 10027007"""
    }

    @Test
    fun `the precision the numbers are good to travels with them`() {
        Fixtures.fellBehind.toJson() shouldContain """"precision": ${Histogram.PRECISION}"""
    }

    @Test
    fun `a reason cannot close the script element it is inlined in`() {
        val json = Fixtures.fellBehind.toJson()

        json shouldNotContain "</script>"
        json shouldNotContain "<b>"
        // The reason itself survives; only the characters that could end the
        // element are written as escapes.
        json shouldContain """\u003c/script\u003e hung up"""
    }

    @Test
    fun `an empty run is still a document, not an empty string`() {
        val empty = RunResult(startedAt = Instant.EPOCH, steps = emptyMap(), behind = Histogram().timing())

        empty.toJson() shouldContain """"steps": []"""
        empty.toJson() shouldContain """"count": 0"""
    }
}
