package io.github.matthewjones372.kestrel.export

import io.github.matthewjones372.kestrel.Histogram
import io.github.matthewjones372.kestrel.Outcome
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.Said
import io.github.matthewjones372.kestrel.StepStats
import io.github.matthewjones372.kestrel.Timing
import io.github.matthewjones372.kestrel.timing
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.HdrHistogram.EncodableHistogram
import org.HdrHistogram.HistogramLogReader
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.random.Random
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

/**
 * HdrHistogram's own reader is the oracle. A hand-written encoder checked
 * against a golden of its own output proves only that it did not change; read
 * back by the library whose format it claims to be, it is either right or it
 * throws.
 */
class HistogramLogTest {

    private val seeded = Random(20260902)

    private fun timingOf(samples: Int, range: LongRange): Timing =
        Histogram().apply {
            repeat(samples) { record(seeded.nextLong(range.first, range.last).microseconds) }
        }.timing()

    private fun runOf(): RunResult {
        val ok = timingOf(2_000, 800L..40_000L)
        val failed = timingOf(37, 100_000L..900_000L)
        return RunResult(
            startedAt = Instant.parse("2026-09-02T09:00:00Z"),
            steps = mapOf(
                "pay" to StepStats(
                    name = "pay",
                    ok = Outcome(ok, ok),
                    failed = Outcome(failed, failed, mapOf(Said("status 503") to 37L)),
                    serviceTime = ok,
                    responseTime = ok,
                ),
            ),
            behind = timingOf(500, 1L..900L),
            hiccups = Histogram().apply { repeat(40) { record(3.milliseconds) } }.timing(),
        )
    }

    /** Every histogram in the log, by the tag it was written under. */
    private fun readBack(log: Path): Map<String, EncodableHistogram> {
        val reader = HistogramLogReader(log.toFile())
        val found = mutableMapOf<String, EncodableHistogram>()
        while (true) {
            val histogram = reader.nextIntervalHistogram() ?: break
            found[histogram.tag] = histogram
        }
        return found
    }

    @Test
    fun `the library that owns the format reads every count back`(@TempDir dir: Path) {
        val run = runOf()
        val read = readBack(run.writeHistogramLog(dir.resolve("run.hlog")))

        withClue("four sides of one step, plus the injector's own two") {
            read.keys.sorted() shouldContainExactly listOf(
                "behind",
                "hiccups",
                "pay.failed.response",
                "pay.failed.service",
                "pay.ok.response",
                "pay.ok.service",
            )
        }
        read.forEach { (tag, histogram) ->
            withClue(tag) { (histogram as org.HdrHistogram.Histogram).totalCount shouldBe expected(run, tag).count }
        }
    }

    @Test
    fun `a percentile read out of the other tool is the percentile read out of this one`(@TempDir dir: Path) {
        val run = runOf()
        val read = readBack(run.writeHistogramLog(dir.resolve("run.hlog")))

        listOf("pay.ok.service", "pay.failed.service", "behind").forEach { tag ->
            val theirs = (read.getValue(tag) as org.HdrHistogram.Histogram)
            withClue("$tag: nothing is re-bucketed, so the two agree exactly") {
                theirs.getValueAtPercentile(99.0) shouldBe expected(run, tag).p99.inWholeNanoseconds
                theirs.getValueAtPercentile(50.0) shouldBe expected(run, tag).p50.inWholeNanoseconds
                theirs.maxValue shouldBe expected(run, tag).max.inWholeNanoseconds
            }
        }
    }

    @Test
    fun `every bucket lands in the slot it was counted in, not a nearby one`(@TempDir dir: Path) {
        val run = runOf()
        val theirs = readBack(run.writeHistogramLog(dir.resolve("run.hlog")))
            .getValue("pay.ok.service") as org.HdrHistogram.Histogram

        val mine = run["pay"].ok.serviceTime.distribution.associate { it.upperBound.inWholeNanoseconds to it.count }
        val across = theirs.recordedValues().associate { it.valueIteratedTo to it.countAtValueIteratedTo }

        withClue("a slot's index here is `countsArrayIndex` there, so this is equality rather than closeness") {
            across shouldBe mine
        }
    }

    @Test
    fun `a timing that counted nothing is left out rather than written empty`(@TempDir dir: Path) {
        val quiet = runOf().copy(hiccups = Timing.none, behind = Timing.none)

        val read = readBack(quiet.writeHistogramLog(dir.resolve("run.hlog")))

        withClue("a line with no samples behind it is a series a reader goes looking for a cause of") {
            read.keys shouldNotContain "hiccups"
            read.keys shouldNotContain "behind"
        }
    }

    @Test
    fun `the log says when the run started, so two of them can be laid beside each other`(@TempDir dir: Path) {
        val written = runOf().writeHistogramLog(dir.resolve("run.hlog")).toFile().readText()

        val started = Instant.parse("2026-09-02T09:00:00Z").toEpochMilli() / 1_000
        written shouldContain "#[StartTime: $started.000"
        HistogramLogReader(dir.resolve("run.hlog").toFile()).nextIntervalHistogram().shouldNotBeNull()
    }

    @Test
    fun `a step named with a comma cannot move the columns under it`(@TempDir dir: Path) {
        val awkward = runOf().let { run ->
            run.copy(steps = mapOf("pay, now" to run["pay"].copy(name = "pay, now")))
        }

        val read = readBack(awkward.writeHistogramLog(dir.resolve("run.hlog")))

        withClue("${read.keys}") { read.keys.contains("pay_now.ok.service") shouldBe true }
    }

    private fun expected(run: RunResult, tag: String): Timing = when (tag) {
        "behind" -> run.behind
        "hiccups" -> run.hiccups
        "pay.ok.service" -> run["pay"].ok.serviceTime
        "pay.ok.response" -> run["pay"].ok.responseTime
        "pay.failed.service" -> run["pay"].failed.serviceTime
        "pay.failed.response" -> run["pay"].failed.responseTime
        else -> error("no timing named $tag")
    }
}
