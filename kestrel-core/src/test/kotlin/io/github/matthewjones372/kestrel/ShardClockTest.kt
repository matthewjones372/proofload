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

/**
 * A missing shard is refused by name already. A clock that disagreed is the
 * half that was silent: every injector waits until *its own* clock reads the
 * instant, so a host running three seconds fast starts three seconds early and
 * writes the same instant as everybody else. The hold it computed is the only
 * place that shows.
 */
class ShardClockTest {

    private val here = Machine(cores = 8, jdk = "21.0.2+13", os = "Linux", arch = "aarch64")

    private val together = Instant.parse("2026-09-03T09:00:00Z")

    private val paying = Plan(
        scenario = "paying",
        steps = listOf("pay"),
        profile = constantRate(100.perSecond, over = 20.seconds),
    )

    private fun shardOf(index: Int, held: Duration?): RunResult {
        val timing = Histogram().apply { repeat(500) { record(10.milliseconds) } }.timing()
        return RunResult(
            startedAt = together,
            steps = mapOf("pay" to StepStats("pay", Outcome(timing, timing), Outcome.none, timing, timing)),
            behind = Histogram().apply { repeat(500) { record(Duration.ZERO) } }.timing(),
            plan = paying,
            machine = here,
            shard = Shard(index = index, of = 2, startingAt = together, heldFor = held),
        )
    }

    @Test
    fun `two injectors whose clocks are a second apart refuse to merge, and say by how much`() {
        val refused = shouldThrow<IllegalArgumentException> {
            Shards(listOf(shardOf(0, held = 30.seconds), shardOf(1, held = 29.seconds)))
        }

        withClue(refused.message.orEmpty()) {
            refused.message.orEmpty() shouldContain "clocks disagree by 1s"
            refused.message.orEmpty() shouldContain "injector 0 held 30s"
            refused.message.orEmpty() shouldContain "injector 1 held 29s"
        }
    }

    @Test
    fun `a difference the timeline cannot see is not a refusal`() {
        val merged = Shards(listOf(shardOf(0, held = 30.seconds), shardOf(1, held = 29950.milliseconds))).merged

        withClue("a twentieth of a bucket smears the picture without touching a percentile") {
            merged["pay"].count shouldBe 1_000L
        }
    }

    @Test
    fun `a bound the caller states is the bound that is used`() {
        val tolerant = Shards(
            listOf(shardOf(0, held = 30.seconds), shardOf(1, held = 29.seconds)),
            tolerating = 2.seconds,
        )

        withClue("a set of hosts nobody synchronises is a set somebody may still want an answer from") {
            tolerant.merged["pay"].count shouldBe 1_000L
        }
    }

    @Test
    fun `a shard that measured no hold is not a refusal for want of a number`() {
        val merged = Shards(listOf(shardOf(0, held = 30.seconds), shardOf(1, held = null))).merged

        withClue("a version 7 baseline has no hold on its shard line, and merging it is not a clock finding") {
            merged["pay"].count shouldBe 1_000L
        }
    }

    @Test
    fun `a shard a caller declared carries no hold, because nothing has measured one yet`() {
        Shard(index = 0, of = 2, startingAt = together).heldFor shouldBe null
    }
}
