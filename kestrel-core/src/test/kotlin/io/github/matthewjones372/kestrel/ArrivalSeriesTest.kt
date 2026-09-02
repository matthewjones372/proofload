package io.github.matthewjones372.kestrel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Real traffic is burstier than an exponential draw, and the burstiness is
 * what fills a queue. A capture is the arrival process with nothing modelled
 * about it, and it has to answer for itself before anything is sent.
 */
class ArrivalSeriesTest {

    @TempDir
    lateinit var dir: Path

    private val noon = Instant.parse("2026-08-26T12:00:00Z")

    /** A file rather than a fake: `csv` is the reader a caller would use. */
    private fun csvOf(vararg columns: Pair<String, List<String>>): CsvFile {
        val rows = columns.first().second.indices.map { row -> columns.joinToString(",") { it.second[row] } }
        val file = dir.resolve("capture.csv")
        Files.write(file, listOf(columns.joinToString(",") { it.first }) + rows)
        return csv(file)
    }

    private fun at(vararg seconds: Long) = seconds.map { noon.plusSeconds(it) }

    @Test
    fun `a capture answers its count and its span before anything departs`() {
        val series = arrivalsFrom(at(0, 1, 2, 3600), source = "friday")

        series.count shouldBe 4
        series.span shouldBe 1.hours
    }

    @Test
    fun `an unordered column is sorted rather than refused`() {
        val jumbled = arrivalsFrom(at(3, 1, 2, 0), source = "grouped by something else")

        withClue("the order of a set of arrivals carries nothing the gaps do not") {
            jumbled.span shouldBe 3.seconds
            jumbled.cov shouldBe arrivalsFrom(at(0, 1, 2, 3), source = "in order").cov
        }
    }

    @Test
    fun `evenly spaced arrivals have no variation and bunched ones do`() {
        arrivalsFrom(at(0, 1, 2, 3, 4), source = "metronome").cov shouldBe 0.0.plusOrMinus(0.0001)

        val bunched = arrivalsFrom(at(0, 1, 2, 3, 100), source = "bursty")
        withClue("four arrivals a second apart and then a long gap") {
            (bunched.cov > 1.0) shouldBe true
        }
    }

    @Test
    fun `fewer than two arrivals is refused by name`() {
        val why = shouldThrow<IllegalArgumentException> { arrivalsFrom(at(0), source = "friday-peak.csv") }

        why.message.orEmpty() shouldContain "friday-peak.csv"
    }

    @Test
    fun `a column the file does not have is named rather than read as empty`() {
        val file = csvOf("when" to listOf("2026-08-26T12:00:00Z", "2026-08-26T12:00:01Z"))

        val why = shouldThrow<IllegalArgumentException> { arrivalsFrom(file, column = "at", source = "peak.csv") }

        why.message.orEmpty() shouldContain "no column named \"at\""
    }

    @Test
    fun `a row that is not an instant names the row and what it held`() {
        val file = csvOf("at" to listOf("2026-08-26T12:00:00Z", "half past two"))

        val why = shouldThrow<IllegalArgumentException> { arrivalsFrom(file, column = "at", source = "peak.csv") }

        why.message.orEmpty() shouldContain "row 2"
        why.message.orEmpty() shouldContain "half past two"
    }

    @Test
    fun `a capture read from a column is the capture those instants make`() {
        val file = csvOf("at" to listOf("2026-08-26T12:00:00Z", "2026-08-26T12:00:02Z", "2026-08-26T12:00:03Z"))

        val series = arrivalsFrom(file, column = "at", source = "peak.csv")

        series.count shouldBe 3
        series.span shouldBe 3.seconds
    }

    @Test
    fun `two captures of the same arrivals are the same value, and of different ones are not`() {
        arrivalsFrom(at(0, 1, 2), source = "one") shouldBe arrivalsFrom(at(0, 1, 2), source = "one")
        (arrivalsFrom(at(0, 1, 2), source = "one") == arrivalsFrom(at(0, 1, 3), source = "one")) shouldBe false
    }
}
