package io.github.matthewjones372.proofload

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
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
        failures: Map<Reason, Long> = emptyMap(),
        steps: List<String> = listOf("pay"),
    ): RunResult {
        val failed = failures.values.sum().toInt()
        val timing = Histogram().apply { repeat(samples) { record(latency) } }.timing()
        val outcomeOf = { taken: Int, reasons: Map<Reason, Long> ->
            val side = Histogram().apply { repeat(taken) { record(latency) } }.timing()
            Outcome(side, side, reasons)
        }
        return RunResult(
            startedAt = startedAt,
            steps = steps.associateWith { name ->
                StepStats(
                    name = name,
                    ok = outcomeOf(samples - failed, emptyMap()),
                    failed = outcomeOf(failed, failures),
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
        val other = Plan("browsing", paying.steps, paying.profile)

        val refusal = shouldThrow<IllegalArgumentException> {
            Runs(listOf(runOf(1.milliseconds), runOf(1.milliseconds, plan = other)))
        }

        refusal.message.orEmpty() shouldContain "run 2 was not asked to do the same thing as the first"
        refusal.message.orEmpty() shouldContain "arm \"browsing\" is sent here and was not before"
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
                runOf(1.milliseconds, failures = mapOf(TimedOut to 3L)),
                runOf(
                    1.milliseconds,
                    failures = mapOf(TimedOut to 2L, Said("500") to 1L),
                    steps = listOf("pay", "refund"),
                ),
            ),
        )

        runs.merged["pay"].failedWith(TimedOut) shouldBe 5L
        runs.merged["pay"].failedWith(Said("500")) shouldBe 1L
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

    private fun recorded(
        over: Int = 3,
        slowSecond: Int = NO_SLOW_SECOND,
        lateBy: Duration = Duration.ZERO,
    ): RunResult {
        val recorder = RunRecorder(Instant.parse("2026-08-26T09:00:00Z"))
        repeat(over) { second ->
            val service = if (second == slowSecond) 400.milliseconds else 10.milliseconds
            repeat(100) { request ->
                recorder.record(
                    step = "pay",
                    failure = null,
                    serviceTime = service,
                    schedulingDelay = lateBy,
                    at = second.seconds + (request * 10).milliseconds,
                )
            }
        }
        return recorder.freeze()
    }

    /** Every field of a [RunResult] and of the [StepStats] under it set to something a default would not give. */
    private fun everyFieldSet(): RunResult {
        val recorder = RunRecorder(Instant.parse("2026-08-26T09:00:00Z"))
        recorder.record("pay", null, 10.milliseconds, schedulingDelay = 2.milliseconds, at = Duration.ZERO)
        recorder.record("pay", Said("status 503"), 20.milliseconds, schedulingDelay = 3.milliseconds, at = 1.seconds)
        recorder.outstanding("pay", Outstanding(unmatched = 2L, inFlight = 1L))
        return recorder.freeze().copy(
            plan = paying,
            arrivals = Arrivals(count = 2L, mean = 500.milliseconds, cov = 0.5),
            machine = here,
            hiccups = Histogram().apply { record(1.milliseconds) }.timing(),
        )
    }

    @Test
    fun `ten runs of the same length merge to a timeline of that length, counting every request`() {
        val runs = Runs(List(10) { recorded(over = 3) })

        runs.merged.timeline.size shouldBe 3
        runs.merged.timeline.sumOf { it.count } shouldBe runs.merged.count
        runs.merged.timeline.first().count shouldBe 1_000L
    }

    @Test
    fun `a merged second superimposes response time as well as service time`() {
        val runs = Runs(List(10) { recorded(over = 3, lateBy = 40.milliseconds) })

        val second = runs.merged.timeline.first()

        withClue("service ${second.serviceTime.p99}, response ${second.responseTime.p99}") {
            second.responseTime.count shouldBe second.serviceTime.count
            (second.serviceTime.p99 < 20.milliseconds) shouldBe true
            (second.responseTime.p99 >= 40.milliseconds) shouldBe true
        }
    }

    @Test
    fun `a step keeps its own seconds through the merge`() {
        val runs = Runs(List(10) { recorded(over = 3) })

        runs.merged["pay"].timeline.size shouldBe 3
        runs.merged["pay"].timeline.sumOf { it.count } shouldBe runs.merged["pay"].count
    }

    @Test
    fun `a second's percentile is read from the runs' buckets rather than averaged across them`() {
        val runs = Runs(List(9) { recorded(over = 3) } + recorded(over = 3, slowSecond = 1))

        val averaged = runs.each.map { it.timeline[1].p99 }.reduce(Duration::plus) / runs.size
        withClue("merged second 1 p99 ${runs.merged.timeline[1].p99}, the average of the ten was $averaged") {
            runs.merged.timeline[1].p99 shouldBeGreaterThanOrEqualTo 400.milliseconds
        }
        runs.merged.timeline[0].p99 shouldBeLessThan 20.milliseconds
    }

    @Test
    fun `the shape ten runs settle into is the shape one of them settles into`() {
        val one = recorded(over = 4, slowSecond = 0)

        val merged = Runs(List(10) { recorded(over = 4, slowSecond = 0) }).merged

        withClue("superimposed, ten identical runs warm up in the same second one of them does") {
            merged.timeline.map { it.p99 } shouldBe one.timeline.map { it.p99 }
        }
    }

    @Test
    fun `runs of different lengths merge to the longest, counting only the runs that reached each second`() {
        val runs = Runs(listOf(recorded(over = 2), recorded(over = 4)))

        runs.merged.timeline.map { it.count } shouldBe listOf(200L, 200L, 100L, 100L)
        runs.merged.timeline.sumOf { it.count } shouldBe runs.merged.count
    }

    @Test
    fun `a second a run never reached is the zero it recorded, not its last second held over`() {
        val short = recorded(over = 2, slowSecond = 1)

        val merged = Runs(listOf(short, recorded(over = 4))).merged

        withClue("the short run's slow last second is in second 1 and nowhere after it") {
            merged.timeline[1].p99 shouldBeGreaterThanOrEqualTo 400.milliseconds
            merged.timeline[2].p99 shouldBeLessThan 20.milliseconds
            merged.timeline[3].p99 shouldBeLessThan 20.milliseconds
        }
    }

    @Test
    fun `runs read back from files carry no timeline, and merge to none rather than being refused`() {
        val runs = tenRuns()

        runs.merged.timeline shouldBe emptyList()
    }

    @Test
    fun `a run with a timeline and one without are not one population`() {
        val refusal = shouldThrow<IllegalArgumentException> {
            Runs(listOf(recorded(over = 3), runOf(10.milliseconds, plan = Plan.none, machine = Machine.here())))
        }

        refusal.message.orEmpty() shouldContain "run 2 has no timeline and the first has 3 seconds"
    }

    @Test
    fun `merging one run gives that run back, so a field nobody merged cannot pass unnoticed`() {
        val one = everyFieldSet()

        withClue("arrivals is the one field a merge drops on purpose, and naming it keeps that a decision") {
            Runs(listOf(one)).merged shouldBe one.copy(arrivals = Arrivals.none)
        }
    }
}

private const val NO_SLOW_SECOND = -1
