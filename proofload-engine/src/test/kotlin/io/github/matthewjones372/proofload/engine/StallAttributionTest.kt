package io.github.matthewjones372.proofload.engine

import io.github.matthewjones372.proofload.Progress
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.fedBy
import io.github.matthewjones372.proofload.feed
import io.github.matthewjones372.proofload.inFlightAtMost
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.sessionKey
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Spec 0145: a refusal is the generator's stall when punctual users alone would have left room. */
class StallAttributionTest {

    private fun Departures.start(punctual: Int, late: Int) {
        repeat(punctual) { admit(CAP, late = false) shouldBe Admission.Started }
        repeat(late) { admit(CAP, late = true) shouldBe Admission.Started }
    }

    @Test
    fun `a cap filled by punctual users is saturation, however late the refused arrival`() {
        val users = Departures().apply { start(punctual = CAP, late = 0) }

        users.admit(CAP, late = false) shouldBe Admission.Saturated
        users.admit(CAP, late = true) shouldBe Admission.Saturated
    }

    @Test
    fun `a cap filled only because late users piled in is the generator's stall`() {
        val users = Departures().apply { start(punctual = 1, late = CAP - 1) }

        users.admit(CAP, late = false) shouldBe Admission.AfterStall
    }

    @Test
    fun `a late user that finishes gives its place back`() {
        val users = Departures().apply { start(punctual = 1, late = CAP - 1) }

        users.finished(late = true)

        users.admit(CAP, late = false) shouldBe Admission.Started
        users.admit(CAP, late = false) shouldBe Admission.AfterStall
    }

    @Test
    fun `once the late users have gone, the same cap reads as saturation again`() {
        val users = Departures().apply { start(punctual = 1, late = CAP - 1) }

        repeat(CAP - 1) { users.finished(late = true) }
        repeat(CAP - 1) { users.admit(CAP, late = false) shouldBe Admission.Started }

        users.admit(CAP, late = false) shouldBe Admission.Saturated
    }

    @Test
    fun `no cap admits everyone`() {
        Departures().admit(cap = null, late = true) shouldBe Admission.Started
    }

    /**
     * The scheduler itself is held: user 0's feeder blocks on the scheduler's
     * thread until the window has passed, so every later arrival falls due
     * while the generator is stalled and then fires in one burst. Whether the
     * run is void is `SaturatedTest`'s: a stall this long also loses ground.
     */
    @Test
    fun `a burst after the scheduler stalled is refused as the generator's`() {
        val stalled = CountDownLatch(1)
        val released = CountDownLatch(1)
        val gates = Progress { elapsed, snapshot ->
            if (elapsed > snapshot.scheduled + 500.milliseconds) stalled.countDown()
            if (elapsed > snapshot.scheduled + 1500.milliseconds) released.countDown()
        }
        val held = scenario("held") { exec("wait") { released.await() } }

        val result = held.at(100.perSecond, over = 1.seconds)
            .fedBy(
                feed(stall) { user ->
                    if (user == 0L) stalled.await()
                    user
                },
            )
            .inFlightAtMost(10)
            .run(gates)

        withClue("ten started, and the ninety refused were all due while the scheduler was held") {
            result["wait"].count shouldBe 10L
            result.refused shouldBe 90L
            result.refusedAfterStall shouldBe 90L
        }
    }

    private companion object {
        const val CAP = 4
        val stall = sessionKey<Long>("stall")
    }
}
