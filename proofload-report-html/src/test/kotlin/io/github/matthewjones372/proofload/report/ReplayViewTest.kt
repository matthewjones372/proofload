package io.github.matthewjones372.proofload.report

import io.github.matthewjones372.proofload.Arrivals
import io.github.matthewjones372.proofload.Plan
import io.github.matthewjones372.proofload.arrivalsFrom
import io.github.matthewjones372.proofload.replaying
import io.kotest.assertions.withClue
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

/**
 * A replayed run must never be described as evenly spaced. That sentence would
 * be wrong on exactly the runs that went to most trouble to be right.
 */
class ReplayViewTest {

    private val noon = Instant.parse("2026-08-26T12:00:00Z")

    private val capture = arrivalsFrom(
        listOf(0L, 10, 20, 30, 900, 910, 1_000).map { noon.plusNanos(it * 1_000_000) },
        source = "friday-peak.csv",
    )

    private fun pageFor(scaled: Double) = Fixtures.fellBehind.copy(
        plan = Plan("checkout", listOf("browse", "pay"), capture.replaying(scaled = scaled)),
        arrivals = Arrivals(count = 7L, mean = 143.milliseconds, cov = capture.cov),
    ).toHtmlReport()

    @Test
    fun `a replayed run names its capture and never says evenly spaced`() {
        val page = pageFor(scaled = 1.0)

        page shouldContain "Arrivals were replayed from friday-peak.csv"
        page shouldNotContain "evenly spaced"
    }

    @Test
    fun `the capture's own burstiness is printed beside what the run produced`() {
        val page = pageFor(scaled = 1.0)

        withClue("the two coefficients are what say whether the replay came out") {
            page shouldContain "whose own coefficient of variation was 1.90"
            page shouldContain "coefficient of variation 1.90"
        }
    }

    @Test
    fun `a scaled replay says by how much`() {
        pageFor(scaled = 2.0) shouldContain "at 2.00x"
    }

    @Test
    fun `an ordinary run still says what it always said`() {
        Fixtures.metItsTarget.toHtmlReport() shouldContain "evenly spaced"
    }
}
