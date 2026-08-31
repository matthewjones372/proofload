package io.github.matthewjones372.kestrel

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class RefreshingTest {

    private val interval = 10.milliseconds

    @Test
    fun `the first value is fetched before the run, not by the user that reads it first`() {
        val fetches = AtomicInteger()

        val token = refreshing(every = 1.minutes) { "token-${fetches.incrementAndGet()}" }

        withClue("a lazy first fetch lands inside whichever user gets there first") {
            fetches.get() shouldBe 1
        }
        token.current shouldBe "token-1"
        token.current shouldBe "token-1"
        token.stop()
    }

    /**
     * Gated on the third fetch rather than the second: the refreshes run in
     * order, so a third that has started is a second already stored.
     */
    @Test
    fun `a refresh replaces the value a step reads`() {
        val fetches = AtomicInteger()
        val stored = CountDownLatch(1)
        val token = refreshing(every = interval) {
            val fetch = fetches.incrementAndGet()
            if (fetch == 3) stored.countDown()
            "token-$fetch"
        }

        withClue("the scheduled refresh never ran") { stored.await(GATE_SECONDS, TimeUnit.SECONDS) shouldBe true }

        token.current shouldNotBe "token-1"
        token.stop()
    }

    @Test
    fun `a refresh runs on a daemon thread of its own, not on the thread that reads the value`() {
        val fetched = CountDownLatch(2)
        val refresher = AtomicReference<Thread>()
        val token = refreshing(every = interval) {
            refresher.set(Thread.currentThread())
            fetched.countDown()
            "token"
        }

        withClue("the scheduled refresh never ran") { fetched.await(GATE_SECONDS, TimeUnit.SECONDS) shouldBe true }

        refresher.get() shouldNotBe Thread.currentThread()
        withClue("a main that forgets to stop the refresher must still exit") {
            refresher.get().isDaemon shouldBe true
        }
        token.stop()
    }

    @Test
    fun `stopping ends the schedule and keeps the value readable`() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val token = refreshing(1.minutes, { "token" }, executor)

        token.stop()

        executor.isShutdown shouldBe true
        token.current shouldBe "token"
    }

    @Test
    fun `a fixed value needs no scheduler at all`() {
        val token = Refreshing.fixed("token")

        token.current shouldBe "token"
        token.stop()
        token.current shouldBe "token"
    }
}

// Long enough that a loaded machine cannot fail these, since what each asserts
// is the change and not how soon it arrived.
private const val GATE_SECONDS = 10L
