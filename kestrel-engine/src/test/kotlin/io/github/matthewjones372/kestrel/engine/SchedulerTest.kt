package io.github.matthewjones372.kestrel.engine

import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.scenario
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

class SchedulerTest {

    @Test
    fun `a virtual user runs on a virtual thread`() {
        val virtual = AtomicBoolean()

        scenario("checkout") { exec("browse") { virtual.set(Thread.currentThread().isVirtual) } }
            .at(1.perSecond, over = 1.seconds)
            .run()

        virtual.get() shouldBe true
    }

    /**
     * The gate is the latch: every user has to reach it before any user may
     * leave, so the run can only finish if all ten were in flight together.
     */
    @Test
    fun `users are in flight at the same time, not one after another`() {
        val users = 10
        val arrived = CountDownLatch(users)

        val result = scenario("checkout") {
            exec("browse") {
                arrived.countDown()
                if (!arrived.await(GATE_SECONDS, TimeUnit.SECONDS)) fail("ran one user at a time")
            }
        }.at(10.perSecond, over = 1.seconds).run()

        result["browse"].ok shouldBe users.toLong()
    }

    @Test
    fun `a fifty a second run for two seconds starts a hundred users, the first finishing before the last`() {
        val users = 100L
        val started = AtomicLong()
        val firstFinished = CountDownLatch(1)
        val firstHadFinished = AtomicBoolean()

        val result = scenario("checkout") {
            exec("browse") {
                val index = started.getAndIncrement()
                if (index == users - 1) firstHadFinished.set(firstFinished.count == 0L)
                if (index == 0L) firstFinished.countDown()
            }
        }.at(50.perSecond, over = 2.seconds).run()

        result["browse"].count shouldBe users
        withClue("the last user to start should have found the first already done") {
            firstHadFinished.get() shouldBe true
        }
    }

    @Test
    fun `lateness is measured against the departure the profile named, not the start of the run`() {
        val result = scenario("checkout") { exec("browse") { } }
            .at(50.perSecond, over = 2.seconds)
            .run()

        result.behind.count shouldBe 100L
        withClue("a delay measured from the start of the run would reach the whole window") {
            result.behind.max shouldBeLessThan 1.seconds
        }
    }

    @Test
    fun `a profile that describes no users measures nothing`() {
        val result = scenario("checkout") { exec("browse") { } }
            .at(0.perSecond, over = 1.seconds)
            .run()

        result.steps.shouldBeEmpty()
        result.count shouldBe 0L
    }

    private companion object {
        /** Long enough that a one-at-a-time engine fails rather than hangs the build. */
        const val GATE_SECONDS = 2L
    }
}
