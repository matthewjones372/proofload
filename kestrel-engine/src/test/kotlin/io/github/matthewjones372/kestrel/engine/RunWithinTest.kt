package io.github.matthewjones372.kestrel.engine

import io.github.matthewjones372.kestrel.Action
import io.github.matthewjones372.kestrel.Allowance
import io.github.matthewjones372.kestrel.Ran
import io.github.matthewjones372.kestrel.Refusal
import io.github.matthewjones372.kestrel.StepScope
import io.github.matthewjones372.kestrel.Targeted
import io.github.matthewjones372.kestrel.at
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.scenario
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * A refusal is worth nothing unless it happens before the first departure, so
 * the action counts what it was actually asked to do rather than the test
 * taking the runner's word for it.
 */
class RunWithinTest {

    private val departed = AtomicInteger()

    @Test
    fun `a run over the allowance sends nothing at all`() {
        val refused = Kestrel().runWithin(Allowance(maxRate = 1.perSecond), paying())

        refused.shouldBeInstanceOf<Ran.Refused>().reason.shouldBeInstanceOf<Refusal.OverRate>()
        withClue("a fence that refuses after the first request is not a fence") {
            departed.get() shouldBe 0
        }
    }

    @Test
    fun `a host nobody allowed sends nothing at all`() {
        val refused = Kestrel().runWithin(Allowance(hosts = listOf("example.invalid")), paying())

        refused.shouldBeInstanceOf<Ran.Refused>().reason.shouldBeInstanceOf<Refusal.HostNotAllowed>()
        departed.get() shouldBe 0
    }

    @Test
    fun `a window over the allowance sends nothing at all`() {
        val refused = Kestrel().runWithin(Allowance(maxDuration = 1.seconds), paying(over = 30.seconds))

        refused.shouldBeInstanceOf<Ran.Refused>().reason.shouldBeInstanceOf<Refusal.OverDuration>()
        departed.get() shouldBe 0
    }

    @Test
    fun `a run inside the allowance is the same run as run itself`() {
        val ran = Kestrel().runWithin(Allowance(maxRate = 100.perSecond, hosts = listOf("orders.internal")), paying())

        ran.shouldBeInstanceOf<Ran.Result>().result.count shouldBe departed.get().toLong()
    }

    @Test
    fun `no allowance bounds nothing`() {
        Kestrel().runWithin(Allowance.none, paying()).shouldBeInstanceOf<Ran.Result>()
    }

    private fun paying(over: kotlin.time.Duration = 1.seconds) = scenario("paying") {
        exec("pay", Counting(departed))
    }.at(5.perSecond, over = over)

    /** A step that reaches a named host and counts every time it is asked to. */
    private class Counting(private val departed: AtomicInteger) : Action, Targeted {

        override val host: String get() = "orders.internal"

        override fun run(scope: StepScope) {
            departed.incrementAndGet()
        }
    }
}
