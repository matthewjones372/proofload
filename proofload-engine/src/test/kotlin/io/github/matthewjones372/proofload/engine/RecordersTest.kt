package io.github.matthewjones372.proofload.engine

import io.github.matthewjones372.proofload.Arrivals
import io.github.matthewjones372.proofload.Plan
import io.github.matthewjones372.proofload.Said
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RecordersTest {

    /**
     * The latch is the gate: no writer starts until every writer is ready, so
     * the samples land while the shards are genuinely contended.
     */
    @Test
    fun `every sample survives being recorded from many users at once`() {
        val recorders = Recorders(Instant.now(), shards = 4)
        val ready = CountDownLatch(WRITERS)

        List(WRITERS) {
            Thread.ofVirtual().start {
                ready.countDown()
                ready.await()
                repeat(EACH) { at ->
                    recorders.record(
                        "browse", null, 1.milliseconds, Duration.ZERO, (at % SECONDS).seconds,
                        reached = at == 0, visit = true, attempts = 1, trace = null,
                    )
                }
            }
        }.forEach { it.join() }

        val result = recorders.freeze(Plan.none, Arrivals.none)
        result["browse"].count shouldBe (WRITERS * EACH).toLong()
        withClue("each writer is one user, and it reached the step on its first request") {
            result["browse"].reached shouldBe WRITERS.toLong()
        }
        withClue("every request here is its own run of the body, and the shards add up") {
            result["browse"].visits shouldBe (WRITERS * EACH).toLong()
        }
        result.behind.count shouldBe (WRITERS * EACH).toLong()

        result.timeline.size shouldBe SECONDS
        result.timeline.sumOf { it.count } shouldBe result.count
    }

    @Test
    fun `a failure recorded on one shard is merged with the successes on the others`() {
        val recorders = Recorders(Instant.now(), shards = 2)

        List(2) { index ->
            Thread.ofVirtual().start {
                recorders.record(
                    "pay",
                    if (index ==
                        0
                    ) Said("503") else null,
                    1.milliseconds, Duration.ZERO, Duration.ZERO,
                    reached = true, visit = true, attempts = 1, trace = null,
                )
            }
        }.forEach { it.join() }

        val pay = recorders.freeze(Plan.none, Arrivals.none)["pay"]
        pay.count shouldBe 2L
        pay.ok.count shouldBe 1L
        pay.failed.reasons shouldBe mapOf(Said("503") to 1L)
    }

    @Test
    fun `there is one recorder per processor, not one per virtual user`() {
        Recorders.defaultShards shouldBe Runtime.getRuntime().availableProcessors()
    }

    private companion object {
        const val WRITERS = 16
        const val EACH = 2_000
        const val SECONDS = 4
    }
}
