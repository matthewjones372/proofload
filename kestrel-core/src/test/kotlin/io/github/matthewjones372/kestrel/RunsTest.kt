package io.github.matthewjones372.kestrel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RunsTest {

    private val here = Machine(cores = 8, jdk = "21.0.2+13", os = "Linux", arch = "aarch64")

    private val paying = Plan(
        scenario = "paying",
        steps = listOf("pay"),
        profile = constantRate(100.perSecond, over = 2.seconds),
    )

    private fun runOf(
        latency: Duration,
        samples: Int = 100,
        startedAt: Instant = Instant.parse("2026-08-26T09:00:00Z"),
        plan: Plan = paying,
        machine: Machine = here,
        failures: Map<String, Long> = emptyMap(),
        steps: List<String> = listOf("pay"),
    ): RunResult {
        val timing = Histogram().apply { repeat(samples) { record(latency) } }.timing()
        return RunResult(
            startedAt = startedAt,
            steps = steps.associateWith { name ->
                StepStats(
                    name = name,
                    count = samples.toLong(),
                    ok = samples.toLong() - failures.values.sum(),
                    failures = failures,
                    serviceTime = timing,
                    responseTime = timing,
                )
            },
            behind = Histogram().apply { repeat(samples) { record(Duration.ZERO) } }.timing(),
            hiccups = Histogram().apply { repeat(samples) { record(1.milliseconds) } }.timing(),
            plan = plan,
            machine = machine,
        )
    }

    private fun tenRuns(latency: Duration = 10.milliseconds): Runs = Runs(List(10) { runOf(latency) })

    @Test
    fun `ten runs merge to one whose count is the sum of theirs`() {
        val runs = tenRuns()

        runs.size shouldBe 10
        runs.merged["pay"].count shouldBe 1_000L
        runs.merged["pay"].serviceTime.count shouldBe 1_000L
        runs.merged.behind.count shouldBe 1_000L
        runs.merged.hiccups.count shouldBe 1_000L
    }

    @Test
    fun `a merged percentile is read from the merged buckets, and the runs behind it are kept`() {
        val slow = runOf(100.milliseconds)
        val runs = Runs(List(9) { runOf(1.milliseconds) } + slow)

        val each = runs.each.map { it["pay"].serviceTime.p99 }
        val averaged = each.reduce(Duration::plus) / runs.size
        withClue("merged p99 ${runs.merged["pay"].serviceTime.p99}, the average of the ten was $averaged") {
            runs.merged["pay"].serviceTime.p99 shouldBe slow["pay"].serviceTime.p99
        }
        each.distinct().size shouldBe 2
    }

    @Test
    fun `the first run is kept and named rather than quietly discarded`() {
        val first = runOf(100.milliseconds)
        val runs = Runs(listOf(first) + List(9) { runOf(1.milliseconds) })

        runs.first shouldBe first
        runs.afterFirst.size shouldBe 9
        withClue("the discarded first run is still in the merged population") {
            runs.merged["pay"].serviceTime.p99 shouldBe first["pay"].serviceTime.p99
        }
    }

    @Test
    fun `runs of two different plans are refused, naming what differs`() {
        val other = paying.copy(scenario = "browsing")

        val refusal = shouldThrow<IllegalArgumentException> {
            Runs(listOf(runOf(1.milliseconds), runOf(1.milliseconds, plan = other)))
        }

        refusal.message.orEmpty() shouldContain "run 2 was not asked to do the same thing as the first"
        refusal.message.orEmpty() shouldContain "scenario was paying, now browsing"
    }

    @Test
    fun `runs measured on two machines are refused rather than pooled`() {
        val laptop = here.copy(cores = 4)

        val refusal = shouldThrow<IllegalArgumentException> {
            Runs(listOf(runOf(1.milliseconds), runOf(1.milliseconds, machine = laptop)))
        }

        refusal.message.orEmpty() shouldContain "run 2 was measured on 4 cores"
    }

    @Test
    fun `nothing to merge is refused, so an empty directory does not read as a result`() {
        shouldThrow<IllegalArgumentException> { Runs(emptyList()) }
    }

    @Test
    fun `failures are summed by reason, and a step only some runs reached keeps the counts of those that did`() {
        val runs = Runs(
            listOf(
                runOf(1.milliseconds, failures = mapOf("timeout" to 3L)),
                runOf(1.milliseconds, failures = mapOf("timeout" to 2L, "500" to 1L), steps = listOf("pay", "refund")),
            ),
        )

        runs.merged["pay"].failedWith("timeout") shouldBe 5L
        runs.merged["pay"].failedWith("500") shouldBe 1L
        runs.merged["refund"].count shouldBe 100L
    }

    @Test
    fun `the merged run started when the earliest of them did`() {
        val early = Instant.parse("2026-08-26T08:00:00Z")
        val runs = Runs.of(runOf(1.milliseconds), runOf(1.milliseconds, startedAt = early))

        runs.merged.startedAt shouldBe early
        runs.merged.plan shouldBe paying
        runs.merged.machine shouldBe here
    }
}
