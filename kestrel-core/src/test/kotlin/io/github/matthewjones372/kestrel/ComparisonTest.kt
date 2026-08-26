package io.github.matthewjones372.kestrel

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

class ComparisonTest {

    private val seeded = Random(20260826)

    private fun runOf(steps: Map<String, LongRange>, samples: Int = 500): RunResult = RunResult(
        startedAt = Instant.parse("2026-08-26T09:00:00Z"),
        steps = steps.mapValues { (name, range) ->
            val timing = Histogram()
                .apply { repeat(samples) { record(seeded.nextLong(range.first, range.last).milliseconds) } }
                .timing()
            StepStats(name, samples.toLong(), samples.toLong(), emptyMap(), timing, timing)
        },
        behind = Histogram().timing(),
    )

    @Test
    fun `two runs of the same target have not been shown to differ`() {
        val change = runOf(mapOf("pay" to 80L..320L)).against(runOf(mapOf("pay" to 80L..320L))).single()

        change.shouldBeInstanceOf<Change.Indistinguishable>()
    }

    @Test
    fun `a run three times slower is worse, and says by how much`() {
        val change = runOf(mapOf("pay" to 800L..1200L)).against(runOf(mapOf("pay" to 80L..320L))).single()

        val worse = change.shouldBeInstanceOf<Change.Worse>()
        (worse.now > worse.before) shouldBe true
    }

    @Test
    fun `a run three times faster is better rather than merely different`() {
        runOf(mapOf("pay" to 20L..40L)).against(runOf(mapOf("pay" to 800L..1200L)))
            .single()
            .shouldBeInstanceOf<Change.Better>()
    }

    @Test
    fun `a step the baseline never had is named as new`() {
        val change = runOf(mapOf("pay" to 80L..120L)).against(runOf(emptyMap())).single()

        change.shouldBeInstanceOf<Change.Added>()
        change.step shouldBe "pay"
    }

    @Test
    fun `a step that stopped running is named, because that is the interesting one`() {
        runOf(emptyMap()).against(runOf(mapOf("pay" to 80L..120L)))
            .single()
            .shouldBeInstanceOf<Change.Gone>()
    }

    @Test
    fun `every step is compared, in a stable order`() {
        val now = runOf(mapOf("pay" to 80L..120L, "browse" to 10L..20L))
        val before = runOf(mapOf("browse" to 10L..20L, "cart" to 30L..40L))

        now.against(before).map { it.step } shouldContainExactly listOf("browse", "cart", "pay")
    }

    @Test
    fun `a run compares as no different from itself`() {
        val run = runOf(mapOf("pay" to 80L..320L))

        run.against(run).single().shouldBeInstanceOf<Change.Indistinguishable>()
    }
}
