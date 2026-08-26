package io.github.matthewjones372.kestrel.baseline

import io.github.matthewjones372.kestrel.Change
import io.github.matthewjones372.kestrel.Histogram
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.StepStats
import io.github.matthewjones372.kestrel.against
import io.github.matthewjones372.kestrel.timing
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

class BaselineTest {

    private val seeded = Random(20260826)

    private fun runOf(name: String = "pay", range: LongRange = 80L..320L, samples: Int = 500): RunResult {
        val timing = Histogram()
            .apply { repeat(samples) { record(seeded.nextLong(range.first, range.last).milliseconds) } }
            .timing()
        return RunResult(
            startedAt = Instant.parse("2026-08-26T09:00:00Z"),
            steps = mapOf(
                name to StepStats(
                    name = name,
                    count = samples.toLong(),
                    ok = samples - 3L,
                    failures = mapOf("status 503" to 3L),
                    serviceTime = timing,
                    responseTime = timing,
                ),
            ),
            behind = Histogram().timing(),
        )
    }

    @Test
    fun `a run written and read back is the same run to compare against`(@TempDir dir: Path) {
        val run = runOf()
        run.writeBaseline(dir.resolve("baseline.kestrel"))

        val read = readBaseline(dir.resolve("baseline.kestrel"))

        read["pay"].count shouldBe run["pay"].count
        read["pay"].responseTime.p99 shouldBe run["pay"].responseTime.p99
        run.against(read).single().shouldBeInstanceOf<Change.Indistinguishable>()
    }

    @Test
    fun `the buckets travel, because an interval cannot be rebuilt from five numbers`(@TempDir dir: Path) {
        val run = runOf()
        run.writeBaseline(dir.resolve("b.kestrel"))

        val read = readBaseline(dir.resolve("b.kestrel"))

        read["pay"].responseTime.distribution shouldBe run["pay"].responseTime.distribution
    }

    @Test
    fun `a slower run compares as worse against what was kept`(@TempDir dir: Path) {
        runOf(range = 80L..120L).writeBaseline(dir.resolve("b.kestrel"))

        val change = runOf(range = 800L..1_200L).against(readBaseline(dir.resolve("b.kestrel"))).single()

        change.shouldBeInstanceOf<Change.Worse>()
    }

    @Test
    fun `a step named with a tab cannot split a line`(@TempDir dir: Path) {
        val awkward = runOf(name = "pay\tnow")
        awkward.writeBaseline(dir.resolve("b.kestrel"))

        readBaseline(dir.resolve("b.kestrel")).steps.keys shouldBe setOf("pay\tnow")
    }

    @Test
    fun `a file that is not a baseline says so rather than half-reading it`() {
        shouldThrow<IllegalArgumentException> { parseBaseline("some other file\nentirely\n") }
    }

    @Test
    fun `a baseline from a future version is refused rather than guessed at`() {
        shouldThrow<IllegalArgumentException> { parseBaseline("kestrel-baseline\t99\nrun\t2026-08-26T09:00:00Z\n") }
    }
}
