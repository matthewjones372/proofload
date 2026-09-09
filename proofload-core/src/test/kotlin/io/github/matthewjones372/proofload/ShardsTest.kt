package io.github.matthewjones372.proofload

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Four injectors are one experiment or they are four, and what decides it is
 * whether the set is whole, alike and judged on the schedule each one kept.
 */
class ShardsTest {

    private val here = Machine(cores = 8, jdk = "21.0.2+13", os = "Linux", arch = "aarch64")

    private val together = Instant.parse("2026-08-26T09:00:00Z")

    // 2,000 users over 20 seconds is a departure every 10 ms for the run, and
    // every 40 ms for one injector of four.
    private val paying = Plan(
        scenario = "paying",
        steps = listOf("pay"),
        profile = constantRate(100.perSecond, over = 20.seconds),
    )

    private fun shardOf(
        index: Int,
        of: Int = 4,
        latency: Duration = 10.milliseconds,
        samples: Int = 500,
        late: Duration = Duration.ZERO,
        startedAt: Instant = together,
        machine: Machine = here,
    ): RunResult {
        val timing = Histogram().apply { repeat(samples) { record(latency) } }.timing()
        return RunResult(
            startedAt = startedAt,
            steps = mapOf("pay" to StepStats("pay", Outcome(timing, timing), Outcome.none, timing, timing)),
            behind = Histogram().apply { repeat(samples) { record(late) } }.timing(),
            plan = paying,
            machine = machine,
            shard = Shard(index = index, of = of, startingAt = together),
        )
    }

    private fun four(): Shards = Shards((0 until 4).map { shardOf(it) })

    @Test
    fun `four injectors merge to the counts of all four, with a percentile off the sum`() {
        val merged = four().merged

        merged["pay"].count shouldBe 2_000L
        merged["pay"].serviceTime.p99 shouldBe shardOf(0)["pay"].serviceTime.p99
    }

    @Test
    fun `the merged run is the whole run, not one injector of it`() {
        val merged = four().merged

        withClue("a merged result is what one JVM would have measured, so it is nobody's share") {
            merged.shard shouldBe null
            merged.plan shouldBe paying
        }
    }

    @Test
    fun `an injector is judged on the schedule it kept, which is four intervals wide`() {
        // 30 ms is three of the run's 10 ms intervals, and less than one of
        // this injector's 40 ms ones.
        val injector = shardOf(index = 1, late = 30.milliseconds)

        withClue("shard k's departures are N intervals apart, so the run's interval is four times too tight") {
            injector.lostGround() shouldBe false
        }
    }

    @Test
    fun `one injector losing ground loses it for the run, however well the others did`() {
        val limping = Shards(listOf(shardOf(0), shardOf(1), shardOf(2), shardOf(3, late = 90.milliseconds)))

        limping.lostGround() shouldBe true
        withClue("the merged run reports the worst injector's lateness, not the pool's") {
            limping.merged.behind.p99 shouldBe limping.worst.behind.p99
        }
    }

    @Test
    fun `three injectors of four are refused, and the refusal names the one missing`() {
        val why = shouldThrow<IllegalArgumentException> { Shards(listOf(shardOf(0), shardOf(1), shardOf(3))) }.message

        withClue(why.orEmpty()) { why.orEmpty() shouldContain "2" }
    }

    @Test
    fun `two injectors that both call themselves zero are refused`() {
        val why = shouldThrow<IllegalArgumentException> {
            Shards(listOf(shardOf(0), shardOf(0), shardOf(2), shardOf(3)))
        }.message

        withClue(why.orEmpty()) { why.orEmpty() shouldContain "0" }
    }

    @Test
    fun `an injector on another machine is refused rather than merged`() {
        val elsewhere = shardOf(3, machine = here.copy(cores = 2))

        val why = shouldThrow<IllegalArgumentException> {
            Shards(listOf(shardOf(0), shardOf(1), shardOf(2), elsewhere))
        }.message

        withClue(why.orEmpty()) { why.orEmpty() shouldContain "measured on" }
    }

    @Test
    fun `a run nobody sharded is not one injector of one`() {
        val why = shouldThrow<IllegalArgumentException> {
            Shards(listOf(shardOf(0).copy(shard = null)))
        }.message

        withClue(why.orEmpty()) { why.orEmpty() shouldContain "shard" }
    }

    @Test
    fun `injectors given different instants are two runs, not one`() {
        val other = shardOf(3).let { it.copy(shard = it.shard?.copy(startingAt = together.plusSeconds(600))) }

        val why = shouldThrow<IllegalArgumentException> {
            Shards(listOf(shardOf(0), shardOf(1), shardOf(2), other))
        }.message

        withClue(why.orEmpty()) { why.orEmpty() shouldContain "instant" }
    }

    @Test
    fun `how far apart they actually started is reported rather than refused`() {
        val late = shardOf(3, startedAt = together.plusMillis(240))

        val set = Shards(listOf(shardOf(0), shardOf(1), shardOf(2), late))

        withClue("only the timeline smears, so this is a caveat and not a refusal") {
            set.startedApart shouldBe 240.milliseconds
        }
    }

    @Test
    fun `what one injector was asked for is its share of what the run was asked for`() {
        val recorder = RunRecorder(together)
        repeat(500) { recorder.record("pay", null, 1.milliseconds, Duration.ZERO, at = (it * 40).milliseconds) }
        val injector = recorder.freeze().copy(plan = paying, shard = Shard(1, 4, together))

        withClue("every injector carries the whole plan unmodified, so its own share is derived") {
            injector.offered?.asked?.perSecond shouldBe 25.0
            injector.ownInterval shouldBe 40.milliseconds
        }
    }
}
