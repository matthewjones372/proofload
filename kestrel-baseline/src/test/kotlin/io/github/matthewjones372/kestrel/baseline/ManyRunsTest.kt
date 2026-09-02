package io.github.matthewjones372.kestrel.baseline

import io.github.matthewjones372.kestrel.Histogram
import io.github.matthewjones372.kestrel.Machine
import io.github.matthewjones372.kestrel.Outcome
import io.github.matthewjones372.kestrel.Plan
import io.github.matthewjones372.kestrel.RunResult
import io.github.matthewjones372.kestrel.Runs
import io.github.matthewjones372.kestrel.Shard
import io.github.matthewjones372.kestrel.StepStats
import io.github.matthewjones372.kestrel.constantRate
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.timing
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ManyRunsTest {

    private val paying = Plan(
        scenario = "paying",
        steps = listOf("pay"),
        profile = constantRate(100.perSecond, over = 2.seconds),
    )

    private val here = Machine(cores = 8, jdk = "21.0.2+13", os = "Linux", arch = "aarch64")

    private fun runOf(startedAt: Instant, samples: Int = 100): RunResult {
        val timing = Histogram().apply { repeat(samples) { record(20.milliseconds) } }.timing()
        return RunResult(
            startedAt = startedAt,
            steps = mapOf("pay" to StepStats("pay", Outcome(timing, timing), Outcome.none, timing, timing)),
            behind = Histogram().timing(),
            plan = paying,
            machine = here,
        )
    }

    private fun invocation(number: Int): RunResult =
        runOf(Instant.parse("2026-08-26T09:00:00Z").plusSeconds(number.toLong()))

    @Test
    fun `ten invocations write ten files that read back as one set of runs`(@TempDir directory: Path) {
        repeat(10) { invocation(it).writeInto(directory) }

        withClue("one file per invocation, none of them overwritten") {
            Files.list(directory).use { it.count() } shouldBe 10L
        }
        val runs = Runs.readAll(directory)
        runs.size shouldBe 10
        runs.merged["pay"].count shouldBe 1_000L
        runs.merged["pay"].serviceTime.count shouldBe 1_000L
    }

    @Test
    fun `the runs come back in the order they started, whatever their files are called`(@TempDir directory: Path) {
        listOf(9, 3, 7, 1).forEach { invocation(it).writeInto(directory) }

        Runs.readAll(directory).each.map { it.startedAt } shouldBe listOf(1, 3, 7, 9).map { invocation(it).startedAt }
    }

    @Test
    fun `a file that is not a run is left where it is`(@TempDir directory: Path) {
        invocation(1).writeInto(directory)
        Files.writeString(directory.resolve("notes.txt"), "what we changed between runs")

        Runs.readAll(directory).size shouldBe 1
    }

    @Test
    fun `four injectors aligned to one instant write four files, not one`(@TempDir directory: Path) {
        val together = Instant.parse("2026-08-26T09:00:00Z")
        val shared = runOf(together)

        (0 until 4).forEach { index ->
            shared.copy(shard = Shard(index = index, of = 4, startingAt = together)).writeInto(directory)
        }

        withClue("aligned injectors start in the same millisecond, and may share a pid across hosts") {
            Files.list(directory).use { it.count() } shouldBe 4L
        }
    }

    @Test
    fun `an injector's file says whose it is, so a directory of them reads without opening one`(
        @TempDir directory: Path,
    ) {
        val together = Instant.parse("2026-08-26T09:00:00Z")

        val written = runOf(together).copy(shard = Shard(index = 2, of = 4, startingAt = together)).writeInto(directory)

        written.fileName.toString() shouldContain "2of4"
    }

    @Test
    fun `a directory of injectors is not a set of runs, and says which they are`(@TempDir directory: Path) {
        val together = Instant.parse("2026-08-26T09:00:00Z")
        (0 until 4).forEach { index ->
            runOf(together).copy(shard = Shard(index = index, of = 4, startingAt = together)).writeInto(directory)
        }

        val why = shouldThrow<IllegalArgumentException> { Runs.readAll(directory) }.message.orEmpty()

        withClue(why) { why shouldContain "injectors rather than runs" }
    }

    @Test
    fun `a directory with no runs in it says so rather than reading as one`(@TempDir directory: Path) {
        val refusal = shouldThrow<IllegalArgumentException> { Runs.readAll(directory) }

        refusal.message.orEmpty() shouldContain directory.toString()
    }
}
