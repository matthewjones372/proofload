package io.github.matthewjones372.kestrel.engine

import io.github.matthewjones372.kestrel.Arrivals
import io.github.matthewjones372.kestrel.Plan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

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
                repeat(EACH) { recorders.record("browse", null, 1.milliseconds, Duration.ZERO) }
            }
        }.forEach { it.join() }

        val result = recorders.freeze(Plan.none, Arrivals.none)
        result["browse"].count shouldBe (WRITERS * EACH).toLong()
        result.behind.count shouldBe (WRITERS * EACH).toLong()
    }

    @Test
    fun `a failure recorded on one shard is merged with the successes on the others`() {
        val recorders = Recorders(Instant.now(), shards = 2)

        List(2) { index ->
            Thread.ofVirtual().start {
                recorders.record("pay", if (index == 0) "503" else null, 1.milliseconds, Duration.ZERO)
            }
        }.forEach { it.join() }

        val pay = recorders.freeze(Plan.none, Arrivals.none)["pay"]
        pay.count shouldBe 2L
        pay.ok shouldBe 1L
        pay.failures shouldBe mapOf("503" to 1L)
    }

    @Test
    fun `there is one recorder per processor, not one per virtual user`() {
        Recorders.defaultShards shouldBe Runtime.getRuntime().availableProcessors()
    }

    private companion object {
        const val WRITERS = 16
        const val EACH = 2_000
    }
}
