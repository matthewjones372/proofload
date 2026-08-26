package io.github.matthewjones372.kestrel.engine

import io.github.matthewjones372.kestrel.Rung
import io.github.matthewjones372.kestrel.failureRate
import io.github.matthewjones372.kestrel.perSecond
import io.github.matthewjones372.kestrel.percent
import io.github.matthewjones372.kestrel.scenario
import io.github.matthewjones372.kestrel.step
import io.github.matthewjones372.kestrel.sustainable
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class SearchingTest {

    private val serve = step("serve")

    /**
     * Twenty thousand a second from one scheduler thread is a rate no injector
     * offers, so every rung of this is the generator's own ceiling rather than
     * anything the target did.
     */
    private val beyondTheInjector = scenario("target") { exec(serve) { } }
        .sustainable(
            upTo = 200_000.perSecond,
            holding = 100.milliseconds,
            expecting = listOf(failureRate under 1.percent),
        )

    @Test
    fun `each rung of a search is a real run at its own rate`() {
        val capacity = beyondTheInjector.run()

        capacity.curve.first().rate shouldBe 20_000.perSecond
        withClue("the rung sent what its own rate asked for over the window it held") {
            capacity.curve.first().result.count shouldBe 2_000L
        }
    }

    @Test
    fun `a rung the injector could not offer is void, and the search stops rather than answer with it`() {
        val capacity = beyondTheInjector.run()

        capacity.curve.single().outcome shouldBe Rung.Outcome.Void
        withClue("no goal was missed, so nothing limited the rate") {
            capacity.limitedBy shouldBe null
        }
        withClue("a rate the generator could not offer is not a rate the target sustained") {
            capacity.voided shouldBe true
            capacity.rate shouldBe null
        }
    }

    @Test
    fun `a search run through Kestrel leaves every rung in the summary a failure prints`() {
        val kestrel = Kestrel()

        val capacity = kestrel.run(beyondTheInjector)

        kestrel.summary().toString() shouldContain "serve"
        withClue("one line of the summary for each rung that ran") {
            kestrel.summary().toString().lines().count { it.startsWith("kestrel:") } shouldBe capacity.curve.size
        }
    }
}
